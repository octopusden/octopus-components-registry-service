package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.dto.v4.ActualDisagreement
import org.octopusden.octopus.components.registry.server.dto.v4.ActualRange
import org.octopusden.octopus.components.registry.server.dto.v4.BuildAspectResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentDetailResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ConfigurationRowType
import org.octopusden.octopus.components.registry.server.dto.v4.RegisteredBuildParametersDetail
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import org.octopusden.octopus.components.registry.server.util.JavaVersionComparator
import org.octopusden.octopus.components.registry.server.util.MavenVersionComparator
import org.octopusden.octopus.components.registry.server.util.VersionRangeIntersector

/**
 * Pure computation over already-collapsed ACTUAL ranges — no RMS call, no DB, no Spring. Kept
 * separate from [RMSBuildParametersService] (which owns fetching/caching) and from the v4 mapper
 * layer (which owns wiring these into response DTOs), so both are independently unit-testable.
 */
object RegisteredBuildParametersMapper {
    /** Component/base-config eligibility for ACTUAL — see [detailFor]. */
    private val ELIGIBLE_BUILD_SYSTEMS = setOf("MAVEN", "GRADLE")

    /**
     * The maximum [valueCompare]-ordered value across [ranges], skipping any value [valueCompare]
     * can't order (e.g. a Java value RMS recorded that CRS's comparator can't parse) rather than
     * letting it throw out of the whole rollup — one unparseable value must not take down every
     * other component's summary row alongside it.
     */
    fun rollup(
        ranges: List<BuildRangeCollapser.Run>,
        valueCompare: (String, String) -> Int,
    ): String? =
        ranges
            .map { it.value }
            .filter { isOrderable(it, valueCompare) }
            .maxWithOrNull(Comparator(valueCompare))

    private fun isOrderable(
        value: String,
        valueCompare: (String, String) -> Int,
    ): Boolean =
        try {
            valueCompare(value, value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * RMS's registered Java version when it has any (the [rollup] of [javaRanges]), else the
     * component's own configured `javaVersion` BASE value. Used both for display (list view) and
     * for filtering — the same effective value, so a component that matches the filter is always
     * the same one the list shows.
     */
    fun effectiveJavaVersion(
        baseJavaVersion: String?,
        javaRanges: List<BuildRangeCollapser.Run>,
    ): String? = rollup(javaRanges, JavaVersionComparator::compare) ?: baseJavaVersion?.takeIf { it.isNotBlank() }

    private fun toActualRanges(ranges: List<BuildRangeCollapser.Run>): List<ActualRange> =
        ranges.map { ActualRange(it.versionRange, it.value) }

    /**
     * One [ActualDisagreement] per (configured row, intersecting ACTUAL range, intersecting sub-range)
     * triple whose values disagree — a composite row can intersect one ACTUAL range at more than one
     * sub-range, each checked and named independently.
     */
    fun warnings(
        configuredRows: List<ActualRange>,
        actualRanges: List<BuildRangeCollapser.Run>,
        valuesEqual: (String, String) -> Boolean,
        versionRangeCompare: (String, String) -> Int,
    ): List<ActualDisagreement> =
        configuredRows.flatMap { row ->
            actualRanges.flatMap { actual ->
                VersionRangeIntersector.intersect(row.versionRange, actual.versionRange, versionRangeCompare).mapNotNull { subRange ->
                    if (valuesEqual(row.value, actual.value)) null else ActualDisagreement(subRange, actual.value)
                }
            }
        }

    /**
     * The full detail-view computation. `null` for a non-Maven/Gradle or archived component
     * (archived ones are never swept, so treating them as ineligible avoids a misleading
     * `actualDataUnavailable` reading as an RMS problem). Otherwise a [RegisteredBuildParametersDetail]:
     * `actualDataUnavailable = true` with empty ranges/warnings when [reportComponents] has no entry
     * (never successfully swept), else the collapsed ranges plus computed warnings.
     */
    fun detailFor(
        response: ComponentDetailResponse,
        reportComponents: Map<String, ComponentBuildRanges>,
        versionRangeCompare: (String, String) -> Int,
    ): RegisteredBuildParametersDetail? {
        if (response.archived) return null
        val buildSystem = response.configurations
            .firstOrNull { it.rowType == ConfigurationRowType.BASE }
            ?.build
            ?.buildSystem
        if (buildSystem !in ELIGIBLE_BUILD_SYSTEMS) return null

        val ranges = reportComponents[response.name]
            ?: return RegisteredBuildParametersDetail(emptyList(), emptyList(), emptyList(), emptyList(), actualDataUnavailable = true)

        return RegisteredBuildParametersDetail(
            javaActualRanges = toActualRanges(ranges.javaRanges),
            javaWarnings =
                warnings(
                    configuredRows(response, "build.javaVersion") { it.javaVersion },
                    ranges.javaRanges,
                    JavaVersionComparator::valuesEqual,
                    versionRangeCompare,
                ),
            mavenActualRanges = toActualRanges(ranges.mavenRanges),
            mavenWarnings =
                warnings(
                    configuredRows(response, "build.mavenVersion") { it.mavenVersion },
                    ranges.mavenRanges,
                    MavenVersionComparator::valuesEqual,
                    versionRangeCompare,
                ),
            actualDataUnavailable = false,
        )
    }

    /** Every DEFAULT/OVERRIDDEN row (BASE, or `overriddenAttribute == attribute`) carrying a non-null value for [valueOf]. */
    private fun configuredRows(
        response: ComponentDetailResponse,
        attribute: String,
        valueOf: (BuildAspectResponse) -> String?,
    ): List<ActualRange> =
        response.configurations.mapNotNull { cfg ->
            if (cfg.rowType != ConfigurationRowType.BASE && cfg.overriddenAttribute != attribute) return@mapNotNull null
            val build = cfg.build ?: return@mapNotNull null
            valueOf(build)?.let { ActualRange(cfg.versionRange, it) }
        }
}
