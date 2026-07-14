package org.octopusden.octopus.components.registry.server.teamcity

import java.util.UUID

/**
 * @property hasCdReleaseBuild true when the project has a non-paused buildType inheriting
 *  from the configured CDRelease template ([TeamcityProperties.SyncProperties.cdReleaseTemplateId]).
 *  [TeamcitySyncService] uses it as the tie-breaker among projects sharing a `COMPONENT_NAME`
 *  and [projectVersion] (flagged one, or smallest id, wins).
 * @property projectVersion the TC `PROJECT_VERSION` parameter, or null when absent. A component
 *  may own one project per release line; [TeamcitySyncService] groups candidates by this value.
 */
data class TcProject(
    val id: String,
    val webUrl: String,
    val hasCdReleaseBuild: Boolean,
    val projectVersion: String? = null,
)

fun interface TcProjectFetcher {
    fun findByComponentNames(componentsByName: Map<String, UUID>): Map<UUID, List<TcProject>>
}
