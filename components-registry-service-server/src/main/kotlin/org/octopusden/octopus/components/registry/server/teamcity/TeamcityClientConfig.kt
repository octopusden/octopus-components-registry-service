package org.octopusden.octopus.components.registry.server.teamcity

import mu.KotlinLogging
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties as ExternalTeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

private const val COMPONENT_NAME_PARAM = "COMPONENT_NAME"
private const val PROJECT_VERSION_PARAM = "PROJECT_VERSION"

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

// Direct buildTypes also request `paused` (to ignore paused release builds) and
// `parameters` (to read PROJECT_VERSION from a buildType — see [getProjectVersion]);
// templates keep the base field set. `archived` is requested per project so archived
// projects can be dropped client-side (see [mapTcProjectsToComponentMatches]).
private const val BUILD_TYPE_WITH_PAUSED = "$BUILD_TYPE_REQUIRED,paused,parameters(property(name,value))"
private const val PROJECT_FIELDS =
    "project(id,name,webUrl,href,archived," +
        "parameters(property(name,value))," +
        "buildTypes(buildType($BUILD_TYPE_WITH_PAUSED," +
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
        val projects = response.projects
        log.info { "TC sync: TC returned ${projects.size} projects with $COMPONENT_NAME_PARAM parameter" }

        return mapTcProjectsToComponentMatches(
            projects,
            componentsByName,
            properties.sync.cdReleaseTemplateId,
        )
    }
}

/**
 * Pure mapper (extracted for unit-testability): resolves each project's `COMPONENT_NAME`,
 * looks it up in [componentsByName], and groups the [TcProject]s by component UUID. A project
 * is dropped when it is archived, has build configs that are ALL paused (projects with no
 * build configs are kept), or has a missing/blank/unknown `COMPONENT_NAME`.
 *
 * `COMPONENT_NAME` and `PROJECT_VERSION` may contain TeamCity `%param%` references
 * (e.g. `my-component-%CUSTOMER_NAME%`), resolved recursively via [resolveParam].
 *
 * `hasCdReleaseBuild` is true iff a non-paused buildType inherits (via `template` or
 * `templates`) from [cdReleaseTemplateId]; sub-projects are not walked.
 */
internal fun mapTcProjectsToComponentMatches(
    projects: List<ExternalTeamcityProject>,
    componentsByName: Map<String, UUID>,
    cdReleaseTemplateId: String,
): Map<UUID, List<TcProject>> =
    projects
        .mapNotNull { project ->
            if (project.archived == true) return@mapNotNull null
            val buildTypes = project.buildTypes?.buildTypes
            if (!buildTypes.isNullOrEmpty() && buildTypes.all { it.paused == true }) return@mapNotNull null

            val projectParams = project.parameters.toParamMap()
            val componentName = resolveParam(COMPONENT_NAME_PARAM, projectParams)
                ?: return@mapNotNull null
            val uuid = componentsByName[componentName] ?: return@mapNotNull null
            val projectVersion = getProjectVersion(project, projectParams)
            val hasCdRelease = projectHasCdReleaseBuild(project, cdReleaseTemplateId)
            uuid to TcProject(
                id = project.id,
                webUrl = project.webUrl,
                hasCdReleaseBuild = hasCdRelease,
                projectVersion = projectVersion,
            )
        }
        .groupBy({ it.first }, { it.second })

/**
 * Release line for a project: the project-level `PROJECT_VERSION`, else the first non-paused
 * buildType that declares one; null when neither has it. `%param%` references are resolved via
 * [resolveParam] (a buildType's own params overlaid on the project's).
 */
private fun getProjectVersion(
    project: ExternalTeamcityProject,
    projectParams: Map<String, String>,
): String? {
    resolveParam(PROJECT_VERSION_PARAM, projectParams)?.let { return it }

    return project.buildTypes?.buildTypes
        ?.asSequence()
        ?.filter { it.paused != true }
        ?.firstNotNullOfOrNull { bt ->
            val own = bt.parameters.toParamMap()
            // Only buildTypes that DECLARE the parameter themselves are considered;
            // references are resolved against project params overlaid by the buildType's.
            if (own.containsKey(PROJECT_VERSION_PARAM)) {
                resolveParam(PROJECT_VERSION_PARAM, projectParams + own)
            } else {
                null
            }
        }
}

// Matches a single TeamCity parameter reference: %name% (name is any run without a '%').
private val PARAM_REFERENCE = Regex("%([^%]+)%")

/** Flatten a TC properties block to a name→value map, keeping the FIRST value per name. */
private fun ExternalTeamcityProperties?.toParamMap(): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    this?.properties?.forEach { property -> map.putIfAbsent(property.name, property.value.orEmpty()) }
    return map
}

/**
 * Read [name] from [params], resolve any `%reference%` tokens, and return the trimmed
 * value (null when the parameter is absent or resolves to blank).
 */
private fun resolveParam(name: String, params: Map<String, String>): String? {
    val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return resolveReferences(raw, params, emptySet()).trim().takeIf { it.isNotEmpty() }
}

/**
 * Recursively expand `%param%` references in [value] against [params]. A missing key or a
 * cyclic reference (tracked via [seen]) is left as the literal `%token%`, so this always terminates.
 */
private fun resolveReferences(
    value: String,
    params: Map<String, String>,
    seen: Set<String>,
): String =
    PARAM_REFERENCE.replace(value) { match ->
        val refName = match.groupValues[1]
        val refValue = params[refName]
        if (refValue == null || refName in seen) {
            match.value
        } else {
            resolveReferences(refValue, params, seen + refName)
        }
    }

private fun projectHasCdReleaseBuild(
    project: ExternalTeamcityProject,
    cdReleaseTemplateId: String,
): Boolean =
    project.buildTypes?.buildTypes
        // A paused build config cannot represent an active release build.
        ?.filter { it.paused != true }
        ?.any { bt ->
            bt.template?.id == cdReleaseTemplateId ||
                bt.templates?.buildTypes?.any { it.id == cdReleaseTemplateId } == true
        } == true
