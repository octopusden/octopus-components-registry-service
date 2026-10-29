package org.octopusden.octopus.components.registry.server.service.rms

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.rms.client.DefaultRMSClient
import org.octopusden.octopus.components.registry.server.service.rms.client.RMSBuild
import org.octopusden.octopus.components.registry.server.service.rms.client.RMSBuildsResult
import org.octopusden.octopus.components.registry.server.service.rms.client.RMSClient
import org.springframework.web.client.RestClient

class DefaultRMSClientTest {
    private val client: RMSClient = DefaultRMSClient(RestClient.builder().baseUrl("http://localhost:${wireMock.port()}").build())

    @Test
    @DisplayName("a confirmed empty-builds response is available with no builds")
    fun `confirmed empty response is available with no builds`() {
        wireMock.stubFor(
            get(urlEqualTo("/rest/api/1/builds/component/empty-component?statuses=RC,RELEASE&descending=false"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")),
        )

        assertEquals(RMSBuildsResult.Available(emptyList()), client.getBuilds("empty-component"))
    }

    @Test
    @DisplayName("a null response body is unavailable, not confirmed empty")
    fun `null body response is unavailable`() {
        wireMock.stubFor(
            get(urlEqualTo("/rest/api/1/builds/component/null-body-component?statuses=RC,RELEASE&descending=false"))
                .willReturn(aResponse().withStatus(200)),
        )

        assertEquals(RMSBuildsResult.Unavailable, client.getBuilds("null-body-component"))
    }

    @Test
    @DisplayName(
        "parses version and buildParameters, incl. a null attribute, tolerating unused fields like component/status/hotfix",
    )
    fun `parses version and buildParameters for every build`() {
        wireMock.stubFor(
            get(urlEqualTo("/rest/api/1/builds/component/my-component?statuses=RC,RELEASE&descending=false"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """
                        [
                          {"component":"my-component","version":"1.0","status":"RC","hotfix":false,
                           "buildParameters":{"javaVersion":"17","mavenVersion":null}},
                          {"component":"my-component","version":"1.1","status":"RELEASE","hotfix":false,
                           "buildParameters":{"javaVersion":null,"mavenVersion":"3.3.9"}}
                        ]
                        """.trimIndent(),
                    ),
                ),
        )

        assertEquals(
            RMSBuildsResult.Available(
                listOf(
                    RMSBuild("1.0", javaVersion = "17", mavenVersion = null),
                    RMSBuild("1.1", javaVersion = null, mavenVersion = "3.3.9"),
                ),
            ),
            client.getBuilds("my-component"),
        )
    }

    @Test
    @DisplayName("a 404 is unavailable, not a confirmed empty response")
    fun `404 response is unavailable`() {
        wireMock.stubFor(
            get(urlEqualTo("/rest/api/1/builds/component/missing-component?statuses=RC,RELEASE&descending=false"))
                .willReturn(aResponse().withStatus(404)),
        )

        assertEquals(RMSBuildsResult.Unavailable, client.getBuilds("missing-component"))
    }

    @Test
    @DisplayName("a 5xx response is unavailable")
    fun `5xx response is unavailable`() {
        wireMock.stubFor(
            get(urlEqualTo("/rest/api/1/builds/component/broken-component?statuses=RC,RELEASE&descending=false"))
                .willReturn(aResponse().withStatus(500)),
        )

        assertEquals(RMSBuildsResult.Unavailable, client.getBuilds("broken-component"))
    }

    @Test
    @DisplayName("a connection failure is unavailable")
    fun `connection failure is unavailable`() {
        val unreachableClient = DefaultRMSClient(RestClient.builder().baseUrl("http://localhost:1").build())
        assertEquals(RMSBuildsResult.Unavailable, unreachableClient.getBuilds("any-component"))
    }

    companion object {
        private lateinit var wireMock: WireMockServer

        @JvmStatic
        @BeforeAll
        fun startWireMock() {
            wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())
            wireMock.start()
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMock.stop()
        }
    }
}
