package org.octopusden.octopus.components.registry.server.teamcity

import mu.KotlinLogging
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.CredentialProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

@Configuration
class TeamcityClientConfig {
    @Bean
    fun tcProjectFetcher(properties: TeamcityProperties): TcProjectFetcher = ExternalTcProjectFetcher(properties)
}

/**
 * Adapter that queries TC per-component using the external teamcity-client
 * library. Each call issues one REST GET to find projects carrying
 * `parameter:(name:COMPONENT_NAME,value:<name>,matchType:equals)` — exact
 * value matching avoids false positives from TC's default contains semantics.
 *
 * The client is lazily initialised so that a blank [TeamcityProperties.baseUrl]
 * does not attempt a connection until [findByComponentNames] is actually called.
 * The blank-URL check itself lives in [TeamcitySyncService] (before any DB or
 * HTTP work), so this adapter does not need to defend against it separately.
 *
 * Per-component TC errors are caught and logged: a single TC failure does not
 * abort the remainder of the sync. Components whose TC call fails are treated
 * as no-match for this run (their existing DB values are preserved).
 */
@Suppress("TooGenericExceptionCaught")
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

    override fun findByComponentNames(componentsByName: Map<String, UUID>): Map<UUID, List<TcProject>> {
        val result = mutableMapOf<UUID, MutableList<TcProject>>()
        var tcErrors = 0
        log.info { "TC sync: querying TC for ${componentsByName.size} components (per-component)" }
        for ((name, uuid) in componentsByName) {
            val locator =
                ProjectLocator(
                    parameter =
                        listOf(
                            PropertyLocator(
                                name = "COMPONENT_NAME",
                                value = name,
                                matchType = PropertyLocator.MatchType.EQUALS,
                            ),
                        ),
                )
            try {
                val response = client.getProjectsWithLocatorAndFields(locator, "project(id,name,webUrl)")
                val projects = response.projects.orEmpty().map { p -> TcProject(id = p.id, webUrl = p.webUrl) }
                if (projects.isNotEmpty()) {
                    result.getOrPut(uuid) { mutableListOf() }.addAll(projects)
                }
            } catch (e: Exception) {
                tcErrors++
                log.warn(e) { "TC sync: failed to query TC for component '$name' (id=$uuid) — treating as no-match" }
            }
        }
        log.info {
            "TC sync: TC returned matches for ${result.size}/${componentsByName.size} components" +
                if (tcErrors > 0) " ($tcErrors TC errors)" else ""
        }
        return result
    }
}
