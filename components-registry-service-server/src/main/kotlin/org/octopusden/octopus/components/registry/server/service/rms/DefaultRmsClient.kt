package org.octopusden.octopus.components.registry.server.service.rms

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/** Thin wrapper over RMS's build-listing endpoint — no dependency on RMS's published client artifact. */
internal class DefaultRmsClient(
    private val restClient: RestClient,
) : RmsClient {
    override fun getBuilds(component: String): RmsBuildsResult =
        try {
            val builds =
                restClient
                    .get()
                    .uri("/rest/api/1/builds/component/{component}?statuses=RC,RELEASE&descending=false", component)
                    .retrieve()
                    .body(Array<RmsBuildResponse>::class.java)
                    .orEmpty()
            RmsBuildsResult.Available(builds.map { RmsBuild(it.version, it.buildParameters.javaVersion, it.buildParameters.mavenVersion) })
        } catch (e: RestClientException) {
            log.warn("Failed to fetch RMS builds for component '{}': {}", component, e.message)
            RmsBuildsResult.Unavailable
        }

    private companion object {
        private val log = LoggerFactory.getLogger(DefaultRmsClient::class.java)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class RmsBuildResponse(
    val version: String,
    val buildParameters: RmsBuildParametersResponse = RmsBuildParametersResponse(),
)

private data class RmsBuildParametersResponse(
    val javaVersion: String? = null,
    val mavenVersion: String? = null,
)
