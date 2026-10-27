package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPathPreview
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewResponse
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewSource
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.releng.versions.IVersionInfo
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory

// ============================================================
// Version-range containment as the resolver reads it. Shared by
// toResolvedEscrowModuleConfig, ComponentCodeRenderer.renderResolved and the
// SYS-106 distribution preview so the three can never disagree.
// ============================================================

private val WHITESPACE = Regex("\\s+")

/** The form a v4 write stores a range in (same rule as ComponentManagementServiceImpl.normalizeRange): no whitespace. */
internal fun normalizeVersionRange(range: String): String = range.trim().replace(WHITESPACE, "")

/** Whether [range] contains [version]; null when [range] does not parse. */
internal fun VersionRangeFactory.rangeContainsOrNull(
    range: String?,
    version: IVersionInfo,
): Boolean? =
    try {
        create(range).containsVersion(version)
    } catch (_: Exception) {
        null
    }

/** Override reading: a range that does not parse never matches. */
internal fun VersionRangeFactory.overrideRangeContains(
    range: String?,
    version: IVersionInfo,
): Boolean = rangeContainsOrNull(range, version) == true

/** Coverage reading: a blank, all-versions or unparseable range counts as containing — conservative, never a false 404. */
internal fun VersionRangeFactory.coverageRangeContains(
    range: String?,
    version: IVersionInfo,
): Boolean = range.isNullOrBlank() || range == ALL_VERSIONS || rangeContainsOrNull(range, version) != false

/**
 * MIG-042 / ADR-018 coverage gate. supported = the union of the RANGE_PRESENCE ranges and, for a
 * legacy/API component whose base is itself bounded, the base range. With an all-versions base and
 * no presence ranges there is no restriction. Overrides never extend coverage: after a
 * supported-versions PUT shrinks coverage, an override left outside it must not resolve. A version
 * in a gap between presence ranges is outside the union, as in V1.
 */
internal fun VersionRangeFactory.isInCoverage(
    presenceRanges: List<String?>,
    baseRange: String?,
    version: IVersionInfo,
): Boolean {
    val hasBoundedBase = baseRange != ALL_VERSIONS
    if (!hasBoundedBase && presenceRanges.isEmpty()) return true
    return presenceRanges.any { coverageRangeContains(it, version) } ||
        (hasBoundedBase && coverageRangeContains(baseRange, version))
}

// ============================================================
// SYS-106: distribution preview
// ============================================================

private class CandidateOverride(
    val attribute: String?,
    // Echoed byte-for-byte: the caller maps results back to its draft rows by string equality.
    val sentRange: String,
    val evaluatedRange: String,
)

/**
 * Which source each distribution.* marker path takes for [request]'s version, over the candidate
 * overrides and coverage in [request] (an omitted field = this component's saved state). Candidate
 * ranges are normalised as a save would store them; saved ranges are evaluated as stored, like the
 * resolver. Overlapping matches are reported as CONFLICT rather than resolved positionally.
 *
 * @throws IllegalArgumentException when the version is blank or has no numeric item.
 */
fun ComponentEntity.previewDistribution(
    request: DistributionPreviewRequest,
    versionRangeFactory: VersionRangeFactory,
    numericVersionFactory: NumericVersionFactory,
): DistributionPreviewResponse {
    val version = request.version.trim()
    val numericVersion = runCatching { numericVersionFactory.create(version) }.getOrNull()
    require(version.isNotEmpty() && numericVersion != null && numericVersion.itemsCount > 0) {
        "version: '$version' is not a parseable numeric version"
    }

    val configs = configurations.toList()
    val baseRange = configs.firstOrNull { it.rowType == "BASE" }?.versionRange ?: ALL_VERSIONS
    val presenceRanges =
        request.supportedVersions?.let { sv ->
            if (sv.all == true) emptyList() else sv.ranges.orEmpty().map(::normalizeVersionRange)
        } ?: configs.filter { it.rowType == "RANGE_PRESENCE" }.map { it.versionRange }
    if (!versionRangeFactory.isInCoverage(presenceRanges, baseRange, numericVersion)) {
        return DistributionPreviewResponse(version, supported = false, paths = emptyList())
    }

    val candidates =
        request.fieldOverrides?.map { CandidateOverride(it.overriddenAttribute, it.versionRange, normalizeVersionRange(it.versionRange)) }
            ?: configs.map { CandidateOverride(it.overriddenAttribute, it.versionRange, it.versionRange) }

    val paths =
        MarkerAttributes.ALL
            .filter { it.startsWith(DISTRIBUTION_MARKER_PREFIX) }
            .map { attribute -> versionRangeFactory.pathPreview(attribute, candidates, numericVersion) }
    return DistributionPreviewResponse(version, supported = true, paths = paths)
}

private fun VersionRangeFactory.pathPreview(
    attribute: String,
    candidates: List<CandidateOverride>,
    version: IVersionInfo,
): DistributionPathPreview {
    val matched = mutableListOf<String>()
    val unevaluable = mutableListOf<String>()
    candidates.filter { it.attribute == attribute }.forEach { candidate ->
        when (rangeContainsOrNull(candidate.evaluatedRange, version)) {
            true -> matched += candidate.sentRange
            null -> unevaluable += candidate.sentRange
            false -> Unit
        }
    }
    val source =
        when (matched.size) {
            0 -> DistributionPreviewSource.BASE
            1 -> DistributionPreviewSource.OVERRIDE
            else -> DistributionPreviewSource.CONFLICT
        }
    return DistributionPathPreview(attribute, source, matched, unevaluable)
}

private const val DISTRIBUTION_MARKER_PREFIX = "distribution."
