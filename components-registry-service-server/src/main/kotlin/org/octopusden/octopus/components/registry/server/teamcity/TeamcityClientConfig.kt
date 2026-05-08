package org.octopusden.octopus.components.registry.server.teamcity

import mu.KotlinLogging
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

private const val COMPONENT_NAME_PARAM = "COMPONENT_NAME"

// `href` and `webUrl` are non-nullable on the library's TeamcityProject DTO, so the
// fields spec MUST request them or Jackson throws on deserialisation. We don't read
// `href` ourselves — it just has to round-trip through the client.
//
// `buildTypes(...)` powers the CDRelease tie-breaker for ambiguous matches. Even
// though we only need the buildType id and its template ancestry to detect
// inheritance, the library's [TeamcityBuildType] DTO also has non-nullable
// `name`, `projectId`, `projectName`, and `href` (TC always returns them on
// full-object queries). Jackson would throw on missing required fields if we
// asked for `id` only — so every nested `buildType(...)` here lists all five
// required fields, both for the direct entries in `buildTypes(...)` and for the
// nested entries inside `template(...)` / `templates(buildType(...))`.
//
// Both `template` (legacy single, TC <2018) and `templates` (multi, TC2018+) are
// requested because TC populates only one of the two depending on installation/
// version; checking both keeps the detection robust at minor extra-bytes cost.
// Direct buildTypes only — sub-projects are not walked, on the convention that
// the project carrying COMPONENT_NAME also owns the release build.
private const val BUILD_TYPE_REQUIRED = "id,name,projectId,projectName,href"
private const val PROJECT_FIELDS =
    "project(id,name,webUrl,href," +
        "parameters(property(name,value))," +
        "buildTypes(buildType($BUILD_TYPE_REQUIRED," +
        "template($BUILD_TYPE_REQUIRED)," +
        "templates(buildType($BUILD_TYPE_REQUIRED)))))"

@Configuration
class TeamcityClientConfig {
    @Bean
    fun tcProjectFetcher(properties: TeamcityProperties): TcProjectFetcher = ExternalTcProjectFetcher(properties)
}

/**
 * Adapter that queries TC in a single batched GET for all projects carrying the
 * `COMPONENT_NAME` parameter, then maps the response to UUIDs known to CRS by
 * looking up each project's `COMPONENT_NAME` value in [componentsByName] (client-side
 * grouping).
 *
 * Why batch instead of per-component:
 * - One HTTP call vs. N (registry size).
 * - Avoids version-specific behavior we previously hit where some TC builds silently
 *   return an empty list for `parameter:(name:X,value:Y,matchType:equals)` queries.
 *   The simple `parameter:(name:X)` filter is universally supported.
 *
 * Multiple TC projects sharing the same `COMPONENT_NAME` are all included in the
 * `List<TcProject>` for that component; each carries `hasCdReleaseBuild` derived
 * from the same response so [TeamcitySyncService.applyMatches] can pick the
 * release-flagged one without an extra round-trip. Projects whose `COMPONENT_NAME`
 * is unknown to CRS, missing, or blank are silently dropped.
 *
 * The client is lazily initialised so that a blank [TeamcityProperties.baseUrl]
 * does not attempt a connection until [findByComponentNames] is actually called.
 * The blank-URL check itself lives in [TeamcitySyncService] (before any DB or HTTP
 * work), so this adapter does not need to defend against it separately.
 */
internal class ExternalTcProjectFetcher(
    private val properties: TeamcityProperties,
) : TcProjectFetcher {
    private val log = KotlinLogging.logger {}

    // Uses the library's own ObjectMapper default (FAIL_ON_UNKNOWN_PROPERTIES=false,
    // NON_NULL serialisation, JavaTimeModule) rather than the Spring-configured bean
    // to avoid potential misconfiguration bleed from application-level customisers.
    private val client: TeamcityClassicClient by lazy {
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl(): String = properties.baseUrl.trimEnd('/')
                override fun getAuth(): CredentialProvider =
                    StandardBasicCredCredentialProvider(properties.username, properties.password)
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override fun findByComponentNames(componentsByName: Map<String, UUID>): Map<UUID, List<TcProject>> {
        if (componentsByName.isEmpty()) return emptyMap()

        val locator = ProjectLocator(parameter = listOf(PropertyLocator(name = COMPONENT_NAME_PARAM)))
        val response = try {
            client.getProjectsWithLocatorAndFields(locator, PROJECT_FIELDS)
        } catch (e: Exception) {
            // Add fetcher-level context before the exception escapes to the admin endpoint
            // (which sees a stack trace) or the scheduler (which logs-and-swallows). Without
            // this line, the upstream message gives no hint that TC sync was the caller.
            log.error(e) {
                "TC sync: batch query failed against ${properties.baseUrl} " +
                    "(${componentsByName.size} components scanned)"
            }
            throw e
        }
        val projects = response.projects.orEmpty()
        log.info { "TC sync: TC returned ${projects.size} projects with $COMPONENT_NAME_PARAM parameter" }

        return mapTcProjectsToComponentMatches(
            projects,
            componentsByName,
            properties.sync.cdReleaseTemplateId,
        )
    }
}

/**
 * Pure mapper, extracted for unit-testability. For each TC project, reads the
 * `COMPONENT_NAME` parameter value, looks it up in [componentsByName], and groups
 * the resulting [TcProject]s by component UUID. Projects without the parameter,
 * with a blank value, or whose value is unknown to CRS are dropped.
 *
 * `hasCdReleaseBuild` is computed per-project from the same response: true iff
 * any direct [ExternalTeamcityProject.buildTypes] entry inherits — through either
 * the legacy single `template` or the multi `templates` link — from the
 * configured [cdReleaseTemplateId]. Inheritance is checked **only** at the
 * project carrying the COMPONENT_NAME parameter; sub-projects are not walked.
 */
internal fun mapTcProjectsToComponentMatches(
    projects: List<ExternalTeamcityProject>,
    componentsByName: Map<String, UUID>,
    cdReleaseTemplateId: String,
): Map<UUID, List<TcProject>> =
    projects
        .mapNotNull { project ->
            val componentName = project.parameters?.properties
                ?.firstOrNull { it.name == COMPONENT_NAME_PARAM }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val uuid = componentsByName[componentName] ?: return@mapNotNull null
            val hasCdRelease = projectHasCdReleaseBuild(project, cdReleaseTemplateId)
            uuid to TcProject(id = project.id, webUrl = project.webUrl, hasCdReleaseBuild = hasCdRelease)
        }
        .groupBy({ it.first }, { it.second })

private fun projectHasCdReleaseBuild(
    project: ExternalTeamcityProject,
    cdReleaseTemplateId: String,
): Boolean =
    project.buildTypes?.buildTypes?.any { bt ->
        bt.template?.id == cdReleaseTemplateId ||
            bt.templates?.buildTypes?.any { it.id == cdReleaseTemplateId } == true
    } == true
