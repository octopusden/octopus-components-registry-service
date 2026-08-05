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
    fun rollup(
        ranges: List<BuildRangeCollapser.Run>,
        compare: (String, String) -> Int,
    ): String? = ranges.map { it.value }.maxWithOrNull(Comparator(compare))

    private fun toActualRanges(ranges: List<BuildRangeCollapser.Run>): List<ActualRange> = ranges.map { ActualRange(it.versionRange, it.value) }

    /** One [ActualDisagreement] per (configured row, intersecting ACTUAL range) pair whose values disagree. */
    fun warnings(
        configuredRows: List<ActualRange>,
        actualRanges: List<BuildRangeCollapser.Run>,
        valuesEqual: (String, String) -> Boolean,
        compare: (String, String) -> Int,
    ): List<ActualDisagreement> =
        configuredRows.flatMap { row ->
            actualRanges.mapNotNull { actual ->
                val subRange = VersionRangeIntersector.intersect(row.versionRange, actual.versionRange, compare) ?: return@mapNotNull null
                if (valuesEqual(row.value, actual.value)) null else ActualDisagreement(subRange, actual.value)
            }
        }

    /**
     * The full detail-view computation: `null` for a non-Maven/Gradle component (per [response]'s
     * BASE row); otherwise a [RegisteredBuildParametersDetail] — `actualDataUnavailable = true` and
     * empty ranges/warnings when [reportComponents] has no entry for this component (RMS has never
     * successfully reported it), else the collapsed ranges plus computed warnings.
     */
    fun detailFor(
        response: ComponentDetailResponse,
        reportComponents: Map<String, ComponentBuildRanges>,
        compare: (String, String) -> Int,
    ): RegisteredBuildParametersDetail? {
        val buildSystem = response.configurations.firstOrNull { it.rowType == ConfigurationRowType.BASE }?.build?.buildSystem
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
                    compare,
                ),
            mavenActualRanges = toActualRanges(ranges.mavenRanges),
            mavenWarnings =
                warnings(
                    configuredRows(response, "build.mavenVersion") { it.mavenVersion },
                    ranges.mavenRanges,
                    MavenVersionComparator::valuesEqual,
                    compare,
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

    /**
     * Unlike the summary rollup (which can trust `reportComponents` membership alone, since the
     * sweep only ever tracks Maven/Gradle components), detail must tell "not eligible at all" (→
     * `null`, no field) apart from "eligible but never successfully swept" (→ `actualDataUnavailable
     * = true`) — two different response shapes a missing map entry alone can't distinguish. So this
     * reads the component's *live* build system directly, rather than relying on cache membership.
     */
    private val ELIGIBLE_BUILD_SYSTEMS = setOf("MAVEN", "GRADLE")
}
