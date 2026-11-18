package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory

fun String.formatVersion(
    versionNumericVersionFactory: NumericVersionFactory,
    version: String,
) = versionNumericVersionFactory.create(version).formatVersion(this)

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
