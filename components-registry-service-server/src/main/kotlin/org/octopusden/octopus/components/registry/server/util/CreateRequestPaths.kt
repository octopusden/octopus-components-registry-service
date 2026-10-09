package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.dto.v4.ComponentCreateRequest

/**
 * The free-text create-request paths a profile field rule may name, and how to read each from a
 * [ComponentCreateRequest]. [PATHS] is both the allow-list checked when profiles are loaded and
 * the set [read] accepts, so the two cannot drift.
 *
 * Indexed paths read the first element only; a missing element, aspect or value reads as `null`.
 */
object CreateRequestPaths {
    private val readers: Map<String, (ComponentCreateRequest) -> String?> =
        linkedMapOf(
            "name" to { it.name },
            "displayName" to { it.displayName },
            "clientCode" to { it.clientCode },
            "artifactIds[0].groupPattern" to { it.artifactIds.firstOrNull()?.groupPattern },
            "baseConfiguration.build.buildTasks" to { it.baseConfiguration?.build?.buildTasks },
            "baseConfiguration.vcsEntries[0].vcsPath" to { it.firstVcsEntry()?.vcsPath },
            "baseConfiguration.vcsEntries[0].branch" to { it.firstVcsEntry()?.branch },
            "baseConfiguration.vcsEntries[0].tag" to { it.firstVcsEntry()?.tag },
            "baseConfiguration.jira.projectKey" to { it.jira()?.projectKey },
            "baseConfiguration.jira.versionPrefix" to { it.jira()?.versionPrefix },
            "baseConfiguration.jira.versionFormat" to { it.jira()?.versionFormat },
            "baseConfiguration.jira.lineVersionFormat" to { it.jira()?.lineVersionFormat },
            "baseConfiguration.jira.minorVersionFormat" to { it.jira()?.minorVersionFormat },
            "baseConfiguration.jira.releaseVersionFormat" to { it.jira()?.releaseVersionFormat },
            "baseConfiguration.jira.buildVersionFormat" to { it.jira()?.buildVersionFormat },
            "baseConfiguration.mavenArtifacts[0].groupPattern" to { it.firstMavenArtifact()?.groupPattern },
            "baseConfiguration.mavenArtifacts[0].artifactPattern" to { it.firstMavenArtifact()?.artifactPattern },
            "baseConfiguration.dockerImages[0].imageName" to { it.firstDockerImage()?.imageName },
            "baseConfiguration.dockerImages[0].flavor" to { it.firstDockerImage()?.flavor },
            "baseConfiguration.packages[0].packageName" to { it.firstPackage()?.packageName },
        )

    val PATHS: Set<String> = readers.keys

    fun read(
        request: ComponentCreateRequest,
        path: String,
    ): String? {
        val reader = requireNotNull(readers[path]) { "'$path' is not a create-request path a field rule may name" }
        return reader(request)
    }
}

private fun ComponentCreateRequest.jira() = baseConfiguration?.jira

private fun ComponentCreateRequest.firstVcsEntry() = baseConfiguration?.vcsEntries?.firstOrNull()

private fun ComponentCreateRequest.firstMavenArtifact() = baseConfiguration?.mavenArtifacts?.firstOrNull()

private fun ComponentCreateRequest.firstDockerImage() = baseConfiguration?.dockerImages?.firstOrNull()

private fun ComponentCreateRequest.firstPackage() = baseConfiguration?.packages?.firstOrNull()
