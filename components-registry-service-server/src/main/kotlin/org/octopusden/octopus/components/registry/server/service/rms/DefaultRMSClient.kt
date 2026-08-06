package org.octopusden.octopus.components.registry.server.service.rms

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/** Thin wrapper over RMS build-listing endpoint — no dependency on RMS's published client artifact. */
internal class DefaultRMSClient(
    private val restClient: RestClient,
) : RMSClient {
    override fun getBuilds(component: String): RMSBuildsResult =
        try {
            // A null body is distinct from a genuine `[]` — RMS always serializes zero matching
            // builds as `[]`, so null means something went wrong, not "confirmed empty."
            restClient
                .get()
                .uri("/rest/api/1/builds/component/{component}?statuses=RC,RELEASE&descending=false", component)
                .retrieve()
                .body(Array<RMSBuildResponse>::class.java)
                ?.let { builds ->
                    RMSBuildsResult.Available(
                        builds.map { RMSBuild(it.version, it.buildParameters.javaVersion, it.buildParameters.mavenVersion) },
                    )
                }
                ?: RMSBuildsResult.Unavailable
        } catch (e: RestClientException) {
            log.warn("Failed to fetch RMS builds for component '{}': {}", component, e.message)
            RMSBuildsResult.Unavailable
        }

    private companion object {
        private val log = LoggerFactory.getLogger(DefaultRMSClient::class.java)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RMSBuildResponse(
    val version: String,
    val buildParameters: RMSBuildParametersResponse = RMSBuildParametersResponse(),
)

private data class RMSBuildParametersResponse(
    val javaVersion: String? = null,
    val mavenVersion: String? = null,
)
