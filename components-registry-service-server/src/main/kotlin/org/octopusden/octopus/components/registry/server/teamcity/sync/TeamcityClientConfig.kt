package org.octopusden.octopus.components.registry.server.teamcity.sync

import mu.KotlinLogging
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties as ExternalTeamcityProperties

private const val COMPONENT_NAME_PARAM = "COMPONENT_NAME"
private const val PROJECT_VERSION_PARAM = "PROJECT_VERSION"

// `href`/`webUrl` are non-nullable on the library's TeamcityProject DTO, so the fields spec
// must request them or Jackson throws on deserialisation (we don't read `href` ourselves).
//
// `buildTypes(...)` powers the CDRelease tie-breaker. Its DTO also has non-nullable `name`,
// `projectId`, `projectName`, `href`, so every nested `buildType(...)` (direct entries and
// inside `template(...)`/`templates(...)`) must list all five fields even though only `id`
// and template ancestry are actually used.
//
// Both `template` (legacy single, TC <2018) and `templates` (multi, TC2018+) are requested
// since TC populates only one depending on version. Direct buildTypes only — sub-projects
// aren't walked, on the convention that the project carrying COMPONENT_NAME owns the release build.
private const val BUILD_TYPE_REQUIRED = "id,name,projectId,projectName,href"
private const val PROJECT_FIELDS =
    "project(id,name,webUrl,href,archived," +
        "parameters(property(name,value))," +
        "buildTypes(buildType($BUILD_TYPE_REQUIRED,paused,parameters(property(name,value))," +
        "template($BUILD_TYPE_REQUIRED)," +
        "templates(buildType($BUILD_TYPE_REQUIRED)))))"

@Configuration
class TeamcityClientConfig {
    @Bean
    fun tcProjectFetcher(properties: TeamcityProperties): TcProjectFetcher = ExternalTcProjectFetcher(properties)
}

/**
 * Adapter that queries TC in a single batched GET for all projects carrying the
 * `COMPONENT_NAME` parameter, then maps the response to UUIDs known to CRS by looking up
 * each project's `COMPONENT_NAME` value in [componentsByName] (client-side grouping).
 *
 * Batched (one call vs. N) rather than per-component, and uses the plain `parameter:(name:X)`
 * filter rather than a value-matching one, since some TC builds silently return an empty
 * list for the latter.
 *
 * Multiple TC projects sharing a `COMPONENT_NAME` are all included in that component's
 * `List<TcProject>`, each carrying `hasCdReleaseBuild` so [TeamcitySyncService.applyMatches]
 * can pick the release-flagged one without an extra round-trip. Projects with an unknown,
 * missing, or blank `COMPONENT_NAME` are silently dropped.
 *
 * The client is lazily initialised so a blank [TeamcityProperties.baseUrl] doesn't attempt a
 * connection until [findByComponentNames] is called; the blank-URL check itself lives in
 * [TeamcitySyncService].
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

                override fun getAuth(): CredentialProvider = StandardBasicCredCredentialProvider(properties.username, properties.password)
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
                hasCdReleaseBuild = hasCdRelease,
                projectVersion = projectVersion,
            )
        }.groupBy({ it.first }, { it.second })

// A release-line version: dot-separated numbers, optionally a `-N` suffix
// (e.g. `1.2`, `2.2.4`, `03.64.53-2`). Resolved PROJECT_VERSION values that don't
// match are treated as "no version" so junk/placeholder values never become a line.
private val PROJECT_VERSION_FORMAT = Regex("""\d+(\.\d+)*(-\d+)?""")

/**
 * Release line for a project: the project-level `PROJECT_VERSION`, else the version declared
 * by its non-paused build types. `%param%` references are resolved via [resolveParam] (a
 * buildType's own params overlaid on the project's); an unresolved reference or a value not
 * matching [PROJECT_VERSION_FORMAT] counts as no version.
 *
 * The build-type fallback is DETERMINISTIC and conflict-safe: it collects the distinct valid
 * versions across all eligible build types (evaluated in `id` order, independent of the TC
 * API's response order) and returns the single value if they agree, or null if they conflict
 * (or none declares one). This prevents repeated syncs from flipping a project's line without
 * an actual TeamCity configuration change.
 */
private fun getProjectVersion(
    project: ExternalTeamcityProject,
    projectParams: Map<String, String>,
): String? {
    resolveProjectVersion(projectParams)?.let { return it }

    val buildTypeVersions = project.buildTypes
        ?.buildTypes
        .orEmpty()
        .filter { it.paused != true && it.parameters.toParamMap().containsKey(PROJECT_VERSION_PARAM) }
        .sortedBy { it.id }
        .mapNotNull { bt -> resolveProjectVersion(projectParams + bt.parameters.toParamMap()) }
        .distinct()
    // Exactly one agreed version → use it; none or conflicting → ambiguous, no version.
    return buildTypeVersions.singleOrNull()
}

/** Resolve `PROJECT_VERSION` from [params] and accept it only if it is a valid version string. */
private fun resolveProjectVersion(params: Map<String, String>): String? =
    resolveParam(PROJECT_VERSION_PARAM, params)?.takeIf { PROJECT_VERSION_FORMAT.matches(it) }

// Matches a single TeamCity parameter reference: %name% (name is any run without a '%').
private val PARAM_REFERENCE = Regex("%([^%]+)%")

/** Flatten a TC properties block to a name→value map, keeping the FIRST value per name. */
private fun ExternalTeamcityProperties?.toParamMap(): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    this?.properties?.forEach { property -> map.putIfAbsent(property.name, property.value.orEmpty()) }
    return map
}

/**
 * Read [name] from [params], resolve any `%reference%` tokens, and return the trimmed value.
 * Returns null when the parameter is absent, resolves to blank, or still contains an
 * UNRESOLVED reference — i.e. a `%...%` token whose key was missing or formed a cycle. An
 * unresolvable value is treated as "no value" (applies to both `COMPONENT_NAME` and
 * `PROJECT_VERSION`).
 */
private fun resolveParam(
    name: String,
    params: Map<String, String>,
): String? {
    val raw = params[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val resolved = resolveReferences(raw, params, emptySet()).trim()
    return resolved.takeIf { it.isNotEmpty() && !PARAM_REFERENCE.containsMatchIn(it) }
}

/**
 * Recursively expand `%param%` references in [value] against [params]. A missing key or a
 * cyclic reference (tracked via [seen]) is left as the literal `%token%` (and [resolveParam]
 * then rejects the whole value), so this always terminates.
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
    project.buildTypes
        ?.buildTypes
        ?.filter { it.paused != true }
        ?.any { bt ->
            bt.template?.id == cdReleaseTemplateId ||
                bt.templates?.buildTypes?.any { it.id == cdReleaseTemplateId } == true
        } == true
