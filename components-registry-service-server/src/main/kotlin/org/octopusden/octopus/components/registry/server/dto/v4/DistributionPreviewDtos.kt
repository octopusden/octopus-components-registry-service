package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema

/**
 * SYS-099: request/response DTOs for the distribution preview
 * (`POST /rest/api/4/components/{idOrName}/distribution/preview`).
 *
 * The Portal's Distribution tab posts the unsaved override set and supported
 * versions together with a concrete version; the server answers, per
 * `distribution.*` marker family, which source the resolver would take for that
 * version. Nothing is persisted.
 */
@Schema(
    description = "Distribution preview request: which source each distribution.* marker path takes for `version`, " +
        "over a candidate override set and candidate supported versions. An omitted field means the saved state.",
)
data class DistributionPreviewRequest(
    @field:Schema(
        description = "The concrete version to resolve. Must contain at least one numeric item, otherwise 400.",
        example = "1.2-0003",
    )
    val version: String,
    @field:Schema(
        description = "Candidate override set, the same full-set list a component PATCH sends. When present it " +
            "replaces the saved overrides entirely; only entries on distribution.* attributes are evaluated. " +
            "Omitted = the saved overrides.",
    )
    val fieldOverrides: List<FieldOverrideUpsertRequest>? = null,
    @field:Schema(description = "Candidate supported versions. Omitted = the saved supported versions.")
    val supportedVersions: DistributionPreviewCoverage? = null,
)

@Schema(description = "Candidate supported versions, same shape as the supported-versions PUT.")
data class DistributionPreviewCoverage(
    @field:Schema(description = "true (or no ranges) = every version is supported.")
    val all: Boolean? = null,
    @field:Schema(description = "Supported ranges; a blank or unparseable range counts as covering.")
    val ranges: List<String>? = null,
)

@Schema(description = "Distribution preview verdict for one version.")
data class DistributionPreviewResponse(
    @field:Schema(description = "The version as sent (trimmed).")
    val version: String,
    @field:Schema(description = "false when the version lies outside the supported versions; paths is then empty.")
    val supported: Boolean,
    @field:Schema(
        description = "One entry per distribution.* marker family, in a stable order; empty when supported is false.",
    )
    val paths: List<DistributionPathPreview>,
)

@Schema(description = "The source one distribution.* marker path takes for the version.")
data class DistributionPathPreview(
    @field:Schema(description = "Marker attribute, e.g. distribution.maven.", example = "distribution.maven")
    val attribute: String,
    @field:Schema(
        description = "BASE: no override range contains the version; OVERRIDE: exactly one does; " +
            "CONFLICT: more than one does (a candidate set has not been through write validation).",
    )
    val source: DistributionPreviewSource,
    @field:Schema(description = "versionRange of each matching override, byte-for-byte as it was sent or stored.")
    val matchedRanges: List<String>,
    @field:Schema(
        description = "versionRange of each override on this path that does not parse (never matches), " +
            "byte-for-byte as it was sent or stored.",
    )
    val unevaluableRanges: List<String>,
)

enum class DistributionPreviewSource {
    BASE,
    OVERRIDE,
    CONFLICT,
}
