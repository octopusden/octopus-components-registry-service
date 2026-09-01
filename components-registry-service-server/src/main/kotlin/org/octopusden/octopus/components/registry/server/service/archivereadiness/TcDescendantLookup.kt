package org.octopusden.octopus.components.registry.server.service.archivereadiness

import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private const val DESCENDANT_FIELDS = "project(id,archived)"

sealed class TcDescendantResult {
    data class Found(val projectIds: Set<String>, val archivedIds: Set<String>) : TcDescendantResult()
    object SystemUnavailable : TcDescendantResult()
    data class ProjectAbsent(val reason: String) : TcDescendantResult()
}

/**
 * Queries TeamCity for all projects in the subtree rooted at [projectId] using the
 * `affectedProject` locator, and returns the full set of project ids (including the root
 * itself, which TC excludes from the response) together with the subset that are archived.
 *
 * Lazily initialises the HTTP client so a blank [TeamcityProperties.baseUrl] does not
 * attempt a connection at startup; [findDescendantsAndSelf] returns [TcDescendantResult.SystemUnavailable]
 * immediately when the base URL is blank or when TC is unreachable.
 */
@Service
class TcDescendantLookup(
    private val properties: TeamcityProperties,
    // Allows tests to inject a mock without needing to create a real TCP connection.
    // Production callers leave this null; Spring injects only `properties`.
    private val clientOverride: TeamcityClient? = null,
) {
    private val log = LoggerFactory.getLogger(TcDescendantLookup::class.java)

    // Lazily initialised so a blank baseUrl does not attempt a connection at startup.
    private val lazyClient: TeamcityClient by lazy {
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl(): String = properties.baseUrl.trimEnd('/')
                override fun getAuth(): CredentialProvider =
                    StandardBasicCredCredentialProvider(properties.username, properties.password)
            },
        )
    }

    private fun client(): TeamcityClient = clientOverride ?: lazyClient

    fun findDescendantsAndSelf(projectId: String): TcDescendantResult {
        if (properties.baseUrl.isBlank()) return TcDescendantResult.SystemUnavailable
        return try {
            // affectedProject locator returns ALL projects in the subtree, EXCLUDING the root itself.
            // We wrap projectId in a ProjectLocator(id = projectId) because affectedProject takes
            // a ProjectLocator, not a raw String.
            val locator = ProjectLocator(affectedProject = ProjectLocator(id = projectId))
            val response = client().getProjectsWithLocatorAndFields(locator, DESCENDANT_FIELDS)
            val descendants = response.projects
            val allIds = descendants.map { it.id }.toMutableSet()
            allIds += projectId  // add root back explicitly
            val archivedIds = descendants.filter { it.archived == true }.map { it.id }.toSet()
            TcDescendantResult.Found(allIds, archivedIds)
        } catch (e: Exception) {
            log.warn("TC descendant lookup failed for project $projectId: ${e.message}")
            TcDescendantResult.SystemUnavailable
        }
    }
}
