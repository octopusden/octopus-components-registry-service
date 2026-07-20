package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityValidationProperties
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject

/** Fetches one enriched TeamCity project (build configs + steps + properties) by its project id. */
interface EnrichedTcProjectFetcher {
    /** The enriched project, or `null` if TeamCity no longer returns it (archived / removed / renamed). */
    fun fetch(projectId: String): ExternalTeamcityProject?
}

/**
 * Per-id enriched fetch with a short-TTL cache. By project id (not the `COMPONENT_NAME` locator), so
 * validation is decoupled from sync and never silently drops a stored project.
 */
@Component
class CachingEnrichedTcProjectFetcher(
    private val properties: TeamcityProperties,
    private val validationProperties: TeamcityValidationProperties,
) : EnrichedTcProjectFetcher {
    private val client: TeamcityClassicClient by lazy {
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl(): String = properties.baseUrl.trimEnd('/')

                override fun getAuth(): CredentialProvider = StandardBasicCredCredentialProvider(properties.username, properties.password)
            },
        )
    }

    private data class Cached(
        val project: ExternalTeamcityProject?,
        val at: Instant,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    override fun fetch(projectId: String): ExternalTeamcityProject? {
        val ttl = Duration.ofMinutes(validationProperties.cacheTtlMinutes)
        val now = Instant.now()
        cache[projectId]?.let { if (Duration.between(it.at, now) < ttl) return it.project }
        val fetched = client.getProjectsWithLocatorAndFields(ProjectLocator(id = projectId), FIELDS).projects.firstOrNull()
        cache[projectId] = Cached(fetched, now)
        return fetched
    }

    private companion object {
        // Every buildType node must list the DTO's non-nullable fields or Jackson throws on missing
        // `name`. Mirrors the sync's PROJECT_FIELDS, plus steps.
        private const val BUILD_TYPE_REQUIRED = "id,name,projectId,projectName,href"
        const val FIELDS =
            "project(id,name,webUrl,href," +
                "parameters(property(name,value))," +
                "buildTypes(buildType($BUILD_TYPE_REQUIRED,paused,templateFlag," +
                "parameters(property(name,value))," +
                "template($BUILD_TYPE_REQUIRED),templates(buildType($BUILD_TYPE_REQUIRED))," +
                "steps(step(id,name,type,disabled,inherited,properties(property(name,value)))))))"
    }
}
