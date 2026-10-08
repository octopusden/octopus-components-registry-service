package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.util.CreateRequestPaths

/**
 * A component as a create will store it: trimmed, hidden fields dropped, blanks cleared. The
 * profile check reads this rather than the request, so it judges what is actually saved. An
 * absent flag reads as `false`.
 */
interface CreatedComponent {
    val solution: Boolean
    val distributionExternal: Boolean
    val distributionExplicit: Boolean

    /** The stored value at one of [CreateRequestPaths.PATHS]; a `[0]` path reads the first entry only. */
    fun valueAt(path: String): String?
}

/** [CreatedComponent] over the entity and base configuration row a create has built, before the flush. */
class EntityCreatedComponent(
    private val entity: ComponentEntity,
    private val base: ComponentConfigurationEntity,
) : CreatedComponent {
    override val solution: Boolean get() = entity.solution == true
    override val distributionExternal: Boolean get() = entity.distributionExternal == true
    override val distributionExplicit: Boolean get() = entity.distributionExplicit == true

    override fun valueAt(path: String): String? {
        val reader = requireNotNull(READERS[path]) { "'$path' is not a create-request path a field rule may name" }
        return reader(this)
    }

    private fun firstVcs() = base.vcsEntries.minByOrNull { it.sortOrder }

    private fun firstMaven() = base.mavenArtifacts.minByOrNull { it.sortOrder }

    private fun firstDocker() = base.dockerImages.minByOrNull { it.sortOrder }

    companion object {
        /** Keyed by exactly [CreateRequestPaths.PATHS]. */
        val READERS: Map<String, (EntityCreatedComponent) -> String?> =
            mapOf(
                "name" to { it.entity.componentKey },
                "displayName" to { it.entity.displayName },
                "clientCode" to { it.entity.clientCode },
                "artifactIds[0].groupPattern" to { c ->
                    c.entity.artifactMappings
                        .minByOrNull { it.sortOrder }
                        ?.groupPattern
                },
                "baseConfiguration.build.buildTasks" to { it.base.buildTasks },
                "baseConfiguration.vcsEntries[0].vcsPath" to { it.firstVcs()?.vcsPath },
                "baseConfiguration.vcsEntries[0].branch" to { it.firstVcs()?.branch },
                "baseConfiguration.vcsEntries[0].tag" to { it.firstVcs()?.tag },
                "baseConfiguration.jira.projectKey" to { it.base.jiraProjectKey },
                "baseConfiguration.jira.versionPrefix" to { it.base.jiraVersionPrefix },
                "baseConfiguration.jira.versionFormat" to { it.base.jiraVersionFormat },
                "baseConfiguration.jira.lineVersionFormat" to { it.base.jiraLineVersionFormat },
                "baseConfiguration.jira.minorVersionFormat" to { it.base.jiraMinorVersionFormat },
                "baseConfiguration.jira.releaseVersionFormat" to { it.base.jiraReleaseVersionFormat },
                "baseConfiguration.jira.buildVersionFormat" to { it.base.jiraBuildVersionFormat },
                "baseConfiguration.mavenArtifacts[0].groupPattern" to { it.firstMaven()?.groupPattern },
                "baseConfiguration.mavenArtifacts[0].artifactPattern" to { it.firstMaven()?.artifactPattern },
                "baseConfiguration.dockerImages[0].imageName" to { it.firstDocker()?.imageName },
                "baseConfiguration.dockerImages[0].flavor" to { it.firstDocker()?.flavor },
                "baseConfiguration.packages[0].packageName" to { c ->
                    c.base.packages
                        .minByOrNull { it.sortOrder }
                        ?.packageName
                },
            )
    }
}
