package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames

fun String.formatVersion(
    versionNumericVersionFactory: NumericVersionFactory,
    version: String,
) = versionNumericVersionFactory.create(version).formatVersion(this)

/**
 * Resolves a component version's VCS settings (`$version` and other variables in tag, branch, …)
 * the same way for the Git and the DB resolver; see [vcsSettingsVersion] for the version used.
 */
class VcsSettingsResolver(
    private val versionNames: VersionNames,
    private val jiraComponentVersionFormatter: JiraComponentVersionFormatter,
    private val numericVersionFactory: NumericVersionFactory,
) {
    /**
     * @param versionToRange the requested version normalized against the component's version ranges,
     *   with the range it falls into
     */
    fun resolve(
        component: String,
        requestedVersion: String,
        versionToRange: Pair<JiraComponentVersion, JiraComponentVersionRange>,
    ): VCSSettings {
        val (jiraComponentVersion, jiraComponentVersionRange) = versionToRange
        val buildVersion =
            vcsSettingsVersion(
                requestedVersion,
                jiraComponentVersion,
                jiraComponentVersionRange.component.isHotfixEnabled,
                jiraComponentVersionFormatter,
                numericVersionFactory,
            )
        return ModelConfigPostProcessor(ComponentVersion.create(component, buildVersion), versionNames)
            .resolveVariables(jiraComponentVersionRange.vcsSettings)
    }
}

/**
 * The version substituted for `$version` in a component's VCS settings (tag, branch).
 *
 * [jiraComponentVersion] holds the requested version after non-strict normalization, which is
 * then formatted with the hotfix or build format. Non-strict normalization takes the first
 * format with no more parts than the version has, so a version shorter than the build format
 * may fall through to the major or line format and lose parts: with release format
 * `$major.$minor.$service` and major format `$major`, "2.2" becomes "2", and the tag would be
 * built from "2.0.0". When that happens — the requested version has fewer parts than the build
 * format and normalization dropped a non-zero part of it — the requested version is used as it
 * is. Otherwise the result is unchanged: padded to the build format ("2" -> "2.0.0") or trimmed
 * to it ("2.2.1.5" -> "2.2.1").
 */
fun vcsSettingsVersion(
    requestedVersion: String,
    jiraComponentVersion: JiraComponentVersion,
    hotfixEnabled: Boolean,
    jiraComponentVersionFormatter: JiraComponentVersionFormatter,
    numericVersionFactory: NumericVersionFactory,
): String {
    val component = jiraComponentVersion.component
    val normalizedVersion = jiraComponentVersion.version
    if (!jiraComponentVersionFormatter.matchesBuildVersionFormat(component, requestedVersion, false) &&
        dropsNonZeroPart(numericVersionFactory, requestedVersion, normalizedVersion)
    ) {
        return requestedVersion
    }
    val versionFormat = component.componentVersionFormat
    val buildVersion = versionFormat.buildVersionFormat.formatVersion(numericVersionFactory, normalizedVersion)
    if (hotfixEnabled) {
        val hotfixVersion = versionFormat.hotfixVersionFormat.formatVersion(numericVersionFactory, normalizedVersion)
        if (hotfixVersion == normalizedVersion) {
            return hotfixVersion
        }
    }
    return buildVersion
}

private fun dropsNonZeroPart(
    numericVersionFactory: NumericVersionFactory,
    requestedVersion: String,
    normalizedVersion: String,
): Boolean {
    val requested = numericVersionFactory.create(requestedVersion)
    val normalized = numericVersionFactory.create(normalizedVersion)
    return (normalized.itemsCount until requested.itemsCount).any { requested.getItem(it) != 0 }
}
