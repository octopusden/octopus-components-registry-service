package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema

/**
 * SYS-059: request/response DTOs for the stateless version-format preview
 * (`POST /rest/api/4/versions/preview`).
 *
 * The Portal editor posts the **unsaved** effective Jira formats — a [base]
 * block plus per-range [overrides] — together with an input [version]. The
 * server resolves which range the version falls into, renders the six version
 * coordinates with the SAME formatter / `VersionNames` the persisted
 * `detailed-version` path uses, and returns a
 * [org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion].
 * No persistence, no component lookup, no `buildSystem` (padding / library
 * computation is driven purely by the format templates + `VersionNames`).
 *
 * Field naming mirrors the v4 write contract
 * ([JiraAspectRequest]): `minorVersionFormat` is the "$major.$minor"-shaped
 * format the resolver models internally as `majorVersionFormat`.
 */

/**
 * The set of Jira format fields shared by [VersionPreviewFormats] (the base
 * block) and [VersionPreviewOverride] (a per-range override). Modelled as an
 * interface so range-vs-base merging is one generic overlay: for each field the
 * override value wins when present (non-null), otherwise the base value is used.
 * A `null` field on an override therefore means "inherit the base"; an empty
 * string means "explicitly blank" (mirrors line/release per the formatter's
 * blank-format fallback), matching how the editor already emits `''`/`null`.
 */
interface JiraPreviewFormatFields {
    val minorVersionFormat: String?
    val releaseVersionFormat: String?
    val buildVersionFormat: String?
    val lineVersionFormat: String?
    val hotfixVersionFormat: String?
    val versionPrefix: String?
    val versionFormat: String?
}

@Schema(description = "Effective BASE Jira version formats (the editor's section draft).")
data class VersionPreviewFormats(
    override val minorVersionFormat: String? = null,
    override val releaseVersionFormat: String? = null,
    @field:Schema(description = "Falls back to the release version format when blank.")
    override val buildVersionFormat: String? = null,
    @field:Schema(description = "Falls back to the minor version format when blank.")
    override val lineVersionFormat: String? = null,
    @field:Schema(description = "When present (non-blank), a hotfix coordinate is rendered in the response.")
    override val hotfixVersionFormat: String? = null,
    @field:Schema(description = "Custom-component version prefix (e.g. \"acme\"). Requires versionFormat when set.")
    override val versionPrefix: String? = null,
    @field:Schema(description = "Custom-component wrapper format, e.g. \"\$versionPrefix-\$baseVersionFormat\".")
    override val versionFormat: String? = null,
) : JiraPreviewFormatFields

@Schema(
    description = "A per-range format override. Only the fields that differ from the base need to be set; " +
        "a null field inherits the base. Applied when the input version falls inside versionRange.",
)
data class VersionPreviewOverride(
    @field:Schema(description = "Maven-style version range, e.g. \"(,1.0.107)\" or \"[1.0,2.0)\".", example = "(,1.0.107)")
    val versionRange: String,
    override val minorVersionFormat: String? = null,
    override val releaseVersionFormat: String? = null,
    override val buildVersionFormat: String? = null,
    override val lineVersionFormat: String? = null,
    override val hotfixVersionFormat: String? = null,
    override val versionPrefix: String? = null,
    override val versionFormat: String? = null,
) : JiraPreviewFormatFields

@Schema(
    description = "Stateless version-format preview request: render the six version coordinates for `version` " +
        "from ad-hoc formats (base + per-range overrides) without persisting or looking up a component.",
)
data class VersionPreviewRequest(
    @field:Schema(description = "The input version that drives range selection and rendering.", example = "1.0.50")
    val version: String,
    @field:Schema(description = "Whether the component is technical. Carried for parity; does not affect rendering.")
    val technical: Boolean = false,
    @field:Schema(
        description = "Whether hotfix versioning is enabled for the component (in the persisted path this derives " +
            "from a configured hotfix VCS branch, NOT from the presence of a hotfix format). A hotfix coordinate is " +
            "rendered only when this is true AND a hotfixVersionFormat resolves for the version.",
    )
    val hotfixEnabled: Boolean = false,
    val base: VersionPreviewFormats,
    @field:Schema(description = "Per-range format overrides. First range that contains the version wins; base otherwise.")
    val overrides: List<VersionPreviewOverride> = emptyList(),
)
