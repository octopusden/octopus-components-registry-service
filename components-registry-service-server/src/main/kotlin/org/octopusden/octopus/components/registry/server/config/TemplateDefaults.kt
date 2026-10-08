package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.util.ComponentDefaultsSeed
import org.octopusden.octopus.components.registry.server.util.TemplateFields

private val DEPRECATED_BUILD_SYSTEMS = setOf("BS2_0")

/**
 * The `component-defaults` values the Portal's create wizard pre-fills, by create-request path
 * (Decision 6). Blank values are left out, a deprecated build system is ignored, and the version
 * formats pair up as the Portal sends them. The Portal's own fallbacks — branch `master`, a
 * built-in full version format — are deliberately not invented here.
 */
fun AdminConfigProperties.ComponentDefaults.templateDefaults(): Map<String, String> {
    val formats = jira?.componentVersionFormat
    val line = formats?.lineVersionFormat.clean()
    val minor = formats?.minorVersionFormat.clean()
    val release = formats?.releaseVersionFormat.clean()
    val build = formats?.buildVersionFormat.clean()
    return listOf(
        TemplateFields.BUILD_SYSTEM to buildSystem.clean()?.takeIf { it !in DEPRECATED_BUILD_SYSTEMS },
        "displayName" to componentDisplayName.clean(),
        ComponentDefaultsSeed.COPYRIGHT to copyright.clean(),
        "baseConfiguration.jira.projectKey" to jira?.projectKey.clean(),
        "baseConfiguration.jira.versionFormat" to formats?.versionFormat.clean(),
        "baseConfiguration.jira.lineVersionFormat" to (line ?: minor),
        "baseConfiguration.jira.minorVersionFormat" to (minor ?: line),
        "baseConfiguration.jira.releaseVersionFormat" to release,
        "baseConfiguration.jira.buildVersionFormat" to build?.takeIf { it != release },
        TemplateFields.ESCROW_GENERATION to escrow?.generation.clean(),
        ComponentDefaultsSeed.VCS_TAG to vcs?.tag.clean(),
        ComponentDefaultsSeed.VCS_BRANCH to vcs?.branch.clean(),
    ).mapNotNull { (path, value) -> value?.let { path to it } }.toMap()
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
