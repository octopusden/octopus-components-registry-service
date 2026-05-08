package org.octopusden.octopus.components.registry.server.teamcity

import java.util.UUID

/**
 * @property hasCdReleaseBuild true when this TC project owns at least one
 *  buildType inheriting from the configured CDRelease template
 *  (see [TeamcityProperties.SyncProperties.cdReleaseTemplateId]). Used by
 *  [TeamcitySyncService] as the tie-breaker when several TC projects carry
 *  the same `COMPONENT_NAME` parameter — the only one (or the lexicographically
 *  smallest of several) flagged `true` wins. Computed once by the fetcher
 *  from the same batch response that resolves the projects, so the field is a
 *  static fact about that response, not a re-query trigger.
 */
data class TcProject(
    val id: String,
    val webUrl: String,
    val hasCdReleaseBuild: Boolean,
)

fun interface TcProjectFetcher {
    fun findByComponentNames(componentsByName: Map<String, UUID>): Map<UUID, List<TcProject>>
}
