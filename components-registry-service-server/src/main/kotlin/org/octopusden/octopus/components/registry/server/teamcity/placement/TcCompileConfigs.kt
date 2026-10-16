package org.octopusden.octopus.components.registry.server.teamcity.placement

import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType

/** ADR-001: the only two chain templates whose build carries `WORK_DIR` / checkout placement. */
private val COMPILE_TEMPLATE_IDS = setOf("CDGradleBuild", "CDJavaMavenBuild")

internal fun TeamcityBuildType.isCompile(): Boolean =
    (templates?.buildTypes.orEmpty().map { it.id } + listOfNotNull(template?.id)).any { it in COMPILE_TEMPLATE_IDS }

internal fun TeamcityBuildType.vcsRootUrls(): List<Pair<String?, String?>> =
    vcsRoots?.entries.orEmpty().map { e ->
        e.vcsRoot.properties
            ?.properties
            .orEmpty()
            .firstOrNull { it.name == "url" }
            ?.value to e.checkoutRules
    }

internal fun TeamcityBuildType.toCompileConfig(): TcCompileConfig =
    TcCompileConfig(
        buildTypeId = id,
        vcsRootEntries = vcsRootUrls().map { (url, rules) -> TcVcsRootEntry(url, rules) },
        workDir = parameters
            ?.properties
            .orEmpty()
            .firstOrNull { it.name == "WORK_DIR" }
            ?.value,
    )

/** The non-paused compile configurations of a project's build types — what the Diff and the VCS-roots check compare. */
fun compileConfigsOf(buildTypes: List<TeamcityBuildType>): List<TcCompileConfig> =
    buildTypes.filter { it.isCompile() && it.paused != true }.map { it.toCompileConfig() }
