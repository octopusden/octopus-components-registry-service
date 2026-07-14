package org.octopusden.octopus.components.registry.server.teamcity

import java.util.UUID

/**
 * @property hasCdReleaseBuild true when this TC project owns at least one
 *  NON-PAUSED buildType inheriting from the configured CDRelease template
 *  (see [TeamcityProperties.SyncProperties.cdReleaseTemplateId]). Used by
 *  [TeamcitySyncService] as the tie-breaker when several TC projects share the
 *  same `COMPONENT_NAME` **and** the same [projectVersion] — the only one (or
 *  the lexicographically smallest of several) flagged `true` wins. Computed
 *  once by the fetcher from the same batch response that resolves the projects,
 *  so the field is a static fact about that response, not a re-query trigger.
 * @property projectVersion value of the TC `PROJECT_VERSION` parameter, or null
 *  when the project does not declare one. A single component may legitimately
 *  own several TC projects, one per release line; [TeamcitySyncService] groups
 *  candidates by this value and keeps one project per distinct version.
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
