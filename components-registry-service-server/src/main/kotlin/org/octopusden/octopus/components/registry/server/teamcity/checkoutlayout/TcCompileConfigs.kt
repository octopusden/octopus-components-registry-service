package org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout

import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog

/**
 * ADR-022: the chain's two compile templates, the only ones whose build carries `WORK_DIR` /
 * checkout layout. Their ids differ between TeamCity installations, so they come from
 * `teamcity.validation.{gradle,maven}-build-template-id`.
 */
fun TemplateCatalog.compileTemplateIds(): Set<String> = setOf(gradleBuildTemplateId, mavenBuildTemplateId) - ""

internal fun TeamcityBuildType.isCompile(compileTemplateIds: Set<String>): Boolean =
    (templates?.buildTypes.orEmpty().map { it.id } + listOfNotNull(template?.id)).any { it in compileTemplateIds }

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
fun compileConfigsOf(
    buildTypes: List<TeamcityBuildType>,
    compileTemplateIds: Set<String>,
): List<TcCompileConfig> =
    buildTypes.filter { it.isCompile(compileTemplateIds) && it.paused != true }.map { it.toCompileConfig() }
