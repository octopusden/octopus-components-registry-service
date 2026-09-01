package org.octopusden.octopus.components.registry.server.service.archivereadiness

import feign.FeignException
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

// `name`/`webUrl`/`href` are non-nullable on the library's TeamcityProject DTO (no defaults), so
// the fields spec must request them or Jackson throws MissingKotlinParameterException on any row
// TC returns — which happens for every real descendant (see TeamcityClientConfig.PROJECT_FIELDS
// and EnrichedTcProjectFetcher.FIELDS for the same lesson applied elsewhere in this codebase).
private const val DESCENDANT_FIELDS = "project(id,name,webUrl,href,archived)"

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
 * `affectedProject` structurally excludes the root project's own data, so the root's `archived`
 * flag is fetched separately via a direct `id:`-locator lookup and merged into the archived set —
 * without this, an archived-but-unshared root project could never be reported as archived.
 *
 * Lazily initialises the HTTP client so a blank [TeamcityProperties.baseUrl] does not
 * attempt a connection at startup; [findDescendantsAndSelf] returns [TcDescendantResult.SystemUnavailable]
 * immediately when the base URL is blank or when TC is unreachable, and [TcDescendantResult.ProjectAbsent]
 * when TC reports (via an empty result or a 404) that [projectId] itself does not exist.
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
            // affectedProject never reports the root project's OWN archived flag (it structurally
            // excludes the root — see the class KDoc), so we fetch the root directly, by id, to
            // learn whether it itself is archived. Same DESCENDANT_FIELDS spec, since the DTO's
            // non-nullable-field requirement applies here just as much as to the descendants query.
            val rootResponse = client().getProjectsWithLocatorAndFields(ProjectLocator(id = projectId), DESCENDANT_FIELDS)
            val rootProject = rootResponse.projects.firstOrNull()
                ?: return TcDescendantResult.ProjectAbsent("TeamCity project $projectId does not exist")

            // affectedProject locator returns ALL projects in the subtree, EXCLUDING the root itself.
            // We wrap projectId in a ProjectLocator(id = projectId) because affectedProject takes
            // a ProjectLocator, not a raw String.
            val locator = ProjectLocator(affectedProject = ProjectLocator(id = projectId))
            val response = client().getProjectsWithLocatorAndFields(locator, DESCENDANT_FIELDS)
            val descendants = response.projects
            val allIds = descendants.map { it.id }.toMutableSet()
            allIds += projectId  // add root back explicitly
            val archivedIds = descendants.filter { it.archived == true }.map { it.id }.toMutableSet()
            if (rootProject.archived == true) archivedIds += projectId
            TcDescendantResult.Found(allIds, archivedIds)
        } catch (e: FeignException.NotFound) {
            // TC told us explicitly that this project id does not resolve — a stronger signal
            // than "system unreachable", and the reason ProjectAbsent (not SystemUnavailable) exists.
            log.info("TC project $projectId does not exist: ${e.message}")
            TcDescendantResult.ProjectAbsent("TeamCity project $projectId does not exist")
        } catch (e: Exception) {
            log.warn("TC descendant lookup failed for project $projectId: ${e.message}")
            TcDescendantResult.SystemUnavailable
        }
    }
}
