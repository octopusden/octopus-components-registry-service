package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.dto.v4.JiraPreviewFormatFields
import org.octopusden.octopus.components.registry.server.dto.v4.VersionPreviewRequest
import org.octopusden.octopus.components.registry.server.mapper.Mapper
import org.octopusden.octopus.components.registry.server.service.VersionPreviewService
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentInfo
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponent
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.ComponentVersionFormat
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.stereotype.Service

@Service
class VersionPreviewServiceImpl(
    versionNames: VersionNames,
    private val detailedComponentVersionMapper: Mapper<JiraComponentVersion, DetailedComponentVersion>,
) : VersionPreviewService {
    // Instance-global VersionNames (server-owned): the same beans the persisted
    // render path binds, so preview padding / custom-var expansion matches.
    private val formatter = JiraComponentVersionFormatter(versionNames)
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)

    override fun preview(request: VersionPreviewRequest): DetailedComponentVersion {
        val version = request.version.trim()
        require(version.isNotBlank()) { "version must not be blank" }
        val numericVersion = numericVersionFactory.create(version)
        require(numericVersion.itemsCount > 0) {
            "version '$version' is not a parseable numeric version"
        }

        val base = request.base

        // Per-FIELD override resolution. Overrides are per-(range, attribute): the
        // editor may override different jira attributes on DIFFERENT ranges (e.g.
        // releaseVersionFormat on (,1.0.107) and hotfixVersionFormat on (,1.2.471)),
        // and one version can fall inside several of them. So each field is resolved
        // independently — the first override that BOTH contains the version AND sets
        // that field wins; otherwise the base. Reading every field off a single
        // "winning override" would drop the other attributes' overrides. Range
        // strings are validated here (a malformed range → IllegalArgumentException
        // → 400 via ControllerExceptionHandler).
        fun effective(select: JiraPreviewFormatFields.() -> String?): String? =
            request.overrides
                .firstOrNull { override ->
                    override.select() != null && versionRangeFactory.create(override.versionRange).containsVersion(numericVersion)
                }?.select()
                ?: base.select()

        val prefix = effective { versionPrefix }
        val wrapperFormat = effective { versionFormat }
        require(prefix.isNullOrBlank() || !wrapperFormat.isNullOrBlank()) {
            "versionFormat is required when versionPrefix is set"
        }

        // minor (modelled as majorVersionFormat) and release are the roots. The
        // persisted DB path (EntityMappers.buildJiraComponent) defaults a blank
        // minor/release to "$major" / "$major.$minor"; mirror that here rather
        // than reject, so an editor draft relying on those defaults previews the
        // same output detailed-version would produce.
        val minorFormat = effective { minorVersionFormat }.orElse(DEFAULT_MINOR_FORMAT)
        val releaseFormat = effective { releaseVersionFormat }.orElse(DEFAULT_RELEASE_FORMAT)
        // Mirror semantics (the resolver relies on Defaults.groovy / the formatter
        // getters for these): a blank line mirrors minor, a blank build mirrors
        // release. The mapper reads these fields verbatim, so they must be
        // non-null even when the caller omits them.
        val lineFormat = effective { lineVersionFormat }.orElse(minorFormat)
        val buildFormat = effective { buildVersionFormat }.orElse(releaseFormat)
        // The persisted buildJiraComponent stores "" (never null) for a missing
        // hotfix format, so a hotfix-eligible component with no format still renders
        // a (degenerate, empty) hotfix coordinate. Match that for exact parity;
        // when not eligible the value is irrelevant (no coordinate is rendered).
        val hotfixFormat = effective { hotfixVersionFormat } ?: if (request.hotfixEnabled) "" else null

        val componentVersionFormat =
            ComponentVersionFormat.create(
                minorFormat,
                releaseFormat,
                buildFormat,
                lineFormat,
                hotfixFormat,
            )
        // Custom-component (prefix) path only kicks in when a prefix is present;
        // otherwise the standard format path renders without a wrapper.
        val componentInfo = if (!prefix.isNullOrBlank()) ComponentInfo(prefix, wrapperFormat) else null
        val jiraComponent =
            JiraComponent(
                PREVIEW_PROJECT_KEY,
                null,
                componentVersionFormat,
                componentInfo,
                request.technical,
                // Hotfix eligibility is VCS-branch-derived in the persisted path
                // (isHotFixEnabled(vcsSettings)), NOT format-derived — so the caller
                // supplies it. A hotfix coordinate then renders only when this is
                // true AND a hotfix format resolves (see JiraComponentVersion.getHotfixVersion).
                request.hotfixEnabled,
            )

        // Canonicalise the input exactly as getJiraComponentVersion does
        // (strict=false): pick the format the version matches and re-render it,
        // then drive ALL coordinates from that clean version. Skipping this would
        // diverge from detailed-version whenever the raw version does not
        // round-trip through the matched format. A version matching no format
        // yields null → 404, mirroring the resolver's not-found.
        val cleanVersion =
            formatter.normalizeVersion(jiraComponent, version, false, request.hotfixEnabled)
                ?: throw NotFoundException("Version '$version' does not match any of the supplied formats")

        val jiraComponentVersion =
            JiraComponentVersion(
                ComponentVersion.create(PREVIEW_COMPONENT_NAME, cleanVersion),
                jiraComponent,
                formatter,
            )
        return detailedComponentVersionMapper.convert(jiraComponentVersion)
    }

    // A blank/null format falls back to the given format.
    private fun String?.orElse(fallback: String): String = if (this.isNullOrBlank()) fallback else this

    companion object {
        // Placeholder identity — preview neither persists nor looks up a
        // component, and the rendered coordinates are independent of the name.
        private const val PREVIEW_PROJECT_KEY = "PREVIEW"
        private const val PREVIEW_COMPONENT_NAME = "preview"

        // Match the persisted DB-path defaults in EntityMappers.buildJiraComponent.
        private const val DEFAULT_MINOR_FORMAT = "\$major"
        private const val DEFAULT_RELEASE_FORMAT = "\$major.\$minor"
    }
}
