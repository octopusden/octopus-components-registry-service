package org.octopusden.octopus.components.registry.server.template

import org.octopusden.octopus.components.registry.server.config.AdminConfigProperties

/**
 * The `component-defaults` values the Portal's create wizard pre-fills, by create-request path
 * (Decision 6). Blank values are left out, a deprecated build system is ignored, and the version
 * formats pair up as the Portal sends them. The Portal's own fallbacks — branch `master`, a
 * built-in full version format — are deliberately not invented here.
 *
 * Which defaults apply to a template, by its classification and build system, is the renderer's
 * decision; this only maps the keys.
 */
object ComponentDefaultsSeed {
    const val COPYRIGHT = "copyright"
    const val VCS_TAG = "baseConfiguration.vcsEntries[0].tag"
    const val VCS_BRANCH = "baseConfiguration.vcsEntries[0].branch"
    private val DEPRECATED_BUILD_SYSTEMS = setOf("BS2_0")

    fun from(defaults: AdminConfigProperties.ComponentDefaults): Map<String, String> {
        val formats = defaults.jira?.componentVersionFormat
        val line = formats?.lineVersionFormat.clean()
        val minor = formats?.minorVersionFormat.clean()
        val release = formats?.releaseVersionFormat.clean()
        val build = formats?.buildVersionFormat.clean()
        return listOf(
            TemplateFields.BUILD_SYSTEM to defaults.buildSystem.clean()?.takeIf { it !in DEPRECATED_BUILD_SYSTEMS },
            "displayName" to defaults.componentDisplayName.clean(),
            COPYRIGHT to defaults.copyright.clean(),
            "baseConfiguration.jira.projectKey" to defaults.jira?.projectKey.clean(),
            "baseConfiguration.jira.versionFormat" to formats?.versionFormat.clean(),
            "baseConfiguration.jira.lineVersionFormat" to (line ?: minor),
            "baseConfiguration.jira.minorVersionFormat" to (minor ?: line),
            "baseConfiguration.jira.releaseVersionFormat" to release,
            "baseConfiguration.jira.buildVersionFormat" to build?.takeIf { it != release },
            TemplateFields.ESCROW_GENERATION to defaults.escrow?.generation.clean(),
            VCS_TAG to defaults.vcs?.tag.clean(),
            VCS_BRANCH to defaults.vcs?.branch.clean(),
        ).mapNotNull { (path, value) -> value?.let { path to it } }.toMap()
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
