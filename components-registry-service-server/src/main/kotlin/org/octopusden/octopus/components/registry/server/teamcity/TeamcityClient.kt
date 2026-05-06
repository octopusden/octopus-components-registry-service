package org.octopusden.octopus.components.registry.server.teamcity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import mu.KotlinLogging
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.time.Duration
import java.util.UUID

/**
 * Domain projection of a TeamCity project, post-flattening.
 *
 * The TC REST projects search returns an arbitrary tree (project →
 * subprojects → buildTypes → ...). For sync we only need the leaf identity
 * (id, display name, webUrl) plus the parameter map so we can extract
 * `COMPONENT_NAME` to key by component name string.
 *
 * `parameters` is a flat name-to-value map; multi-value TC properties are
 * collapsed to the last seen value (the locator search returns one project
 * per match anyway, so collisions are rare).
 */
data class TeamcityProject(
    val id: String,
    val name: String,
    val webUrl: String,
    val parameters: Map<String, String>,
)

/**
 * Thin TC REST client used by the sync engine. Single responsibility: issue
 * one batched lookup of all projects carrying the `COMPONENT_NAME` parameter
 * and group them client-side by parameter value.
 *
 * Why fresh instead of reusing the components-automation Gradle plugin:
 * - That client lives in `buildSrc` and is wired through Apache HttpClient +
 *   custom utilities not on the server classpath.
 * - The plugin does much more than we need (create projects, build types,
 *   wiki publishing); pulling it in would couple the server runtime to a
 *   build-time-only artifact.
 * - We only need a single GET for sync; `RestTemplate` with HTTP Basic is
 *   the simpler dependency-free path.
 *
 * Disabled when `teamcity.base-url` is blank — [findProjectsByComponentParameter]
 * throws [IllegalStateException] so the caller (admin endpoint or scheduler)
 * surfaces the misconfiguration rather than silently returning all-NO_MATCH.
 */
@Component
class TeamcityClient(
    private val properties: TeamcityProperties,
    restTemplateBuilder: RestTemplateBuilder,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Pre-built RestTemplate. Basic-auth headers are injected per request
     * (not via builder.basicAuthentication) so we can probe the disabled-state
     * (blank base URL) without any auth header configuration churn.
     *
     * Connect/read timeouts are bounded — a stalled TC host must not block
     * the synchronous resync (or its caller's HTTP request) indefinitely.
     */
    private val restTemplate: RestTemplate =
        restTemplateBuilder
            .setConnectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
            .build()

    /**
     * Returns a UUID → TC project map for components whose TC project carries
     * `parameter:(name:COMPONENT_NAME,value:<name>)`. Single batched call:
     * `parameter:(name:COMPONENT_NAME)` returns ALL such projects, then we
     * group client-side by name string.
     *
     * [componentsByName] maps component name (string, as stored in TC
     * COMPONENT_NAME parameter) → component UUID.
     *
     * Behaviour contract:
     * - `teamcity.base-url` blank → throws [IllegalStateException] regardless of
     *   input size; caller surfaces it as an error. Checked first so a
     *   misconfigured environment is never silently treated as successful (the
     *   empty-registry fast-path would otherwise mask the missing URL).
     * - Empty input → empty result, no HTTP call.
     * - TC returns multiple projects for the same name → all included in the
     *   raw return; the SyncService is responsible for "skipped_ambiguous".
     * - TC API failure → exception propagates; SyncService catches and counts
     *   as `errors`.
     *
     * NOTE: components-automation BaseComponentsTask stores the v2 CRS component
     * name (not a UUID) as the COMPONENT_NAME parameter value. Matching is done
     * by name string via [componentsByName].
     */
    fun findProjectsByComponentParameter(componentsByName: Map<String, UUID>): Map<UUID, List<TeamcityProject>> {
        val baseUrl = properties.baseUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            throw IllegalStateException(
                "TC sync is not configured: teamcity.base-url is blank. " +
                    "Set teamcity.base-url in service-config (components-registry-service.yml).",
            )
        }
        if (componentsByName.isEmpty()) return emptyMap()

        // Locator: parameter:(name:COMPONENT_NAME) — match every project
        // carrying the parameter regardless of value, then group client-side.
        // matchType:equals requires a value to be specified and is rejected by
        // TC REST API without one; name-only lookup returns all projects that
        // have the parameter, which is exactly what we need.
        val locator = "parameter:(name:COMPONENT_NAME)"
        // `fields=` keeps the response shape minimal — only id/name/webUrl
        // plus the parameter map (we need COMPONENT_NAME's value).
        val fields = "project(id,name,webUrl,parameters(property(name,value)))"
        val url = "$baseUrl/app/rest/projects?locator=$locator&fields=$fields"

        log.info { "TC sync: GET $url" }
        val headers =
            HttpHeaders().apply {
                accept = listOf(MediaType.APPLICATION_JSON)
                setBasicAuth(properties.username, properties.password)
            }
        val response =
            restTemplate.exchange(
                url,
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                ProjectsResponse::class.java,
            )
        val projects = response.body?.project ?: emptyList()
        log.info { "TC sync: received ${projects.size} projects with COMPONENT_NAME parameter" }

        return projects
            .mapNotNull { it.toDomain() }
            .mapNotNull { project ->
                val raw = project.parameters[COMPONENT_NAME_PARAM]?.trim().orEmpty()
                val uuid = componentsByName[raw]
                if (uuid != null) uuid to project else null
            }.groupBy({ it.first }, { it.second })
    }

    companion object {
        const val COMPONENT_NAME_PARAM: String = "COMPONENT_NAME"
    }

    // -- Wire DTOs ----------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ProjectsResponse(
        val project: List<RawProject>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RawProject(
        val id: String? = null,
        val name: String? = null,
        @JsonProperty("webUrl") val webUrl: String? = null,
        val parameters: RawParameters? = null,
    ) {
        fun toDomain(): TeamcityProject? {
            val safeId = id ?: return null
            val safeName = name ?: return null
            val map =
                parameters
                    ?.property
                    ?.associate { (it.name ?: "") to (it.value ?: "") }
                    ?.filterKeys { it.isNotEmpty() }
                    ?: emptyMap()
            // webUrl can legitimately be missing/blank when TC returns a
            // project the sync caller cannot link to. We keep the row in
            // the domain object (so the SyncService can decide); empty string
            // is the contract for "no webUrl".
            return TeamcityProject(
                id = safeId,
                name = safeName,
                webUrl = webUrl ?: "",
                parameters = map,
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RawParameters(
        val property: List<RawProperty>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RawProperty(
        val name: String? = null,
        val value: String? = null,
    )
}
