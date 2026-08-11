package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.rms.ComponentBuildRanges
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersReport
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersService
import org.octopusden.octopus.components.registry.server.service.rms.RMSRefreshScheduler
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

/**
 * Proves `ComponentManagementServiceImpl`'s `as-code` rendering is really wired to
 * `RMSBuildParametersService` end-to-end — `ComponentCodeRendererTest` already covers the
 * rendering logic itself in isolation, but nothing previously exercised the real call-site
 * (`rmsRangesFor` + threading the result into `ComponentCodeRenderer`). Uses the H2 `ft-db`
 * profile with a mocked `RMSBuildParametersService`, so it runs without Docker — mirrors
 * `ComponentRegisteredBuildParametersEmbeddingIntegrationTest`'s harness.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class ComponentAsCodeRmsIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var rmsBuildParametersService: RMSBuildParametersService

    // Neutralizes the real scheduler's dynamic Trigger registration, which otherwise fires against
    // the mocked service on a background thread as soon as the context starts — racing this test's
    // own `when(...)` stubbing calls on the same mock and corrupting Mockito's ongoing-stubbing state.
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var rmsRefreshScheduler: RMSRefreshScheduler

    @Autowired
    private lateinit var mvc: MockMvc

    init {
        val testResourcesPath =
            Paths.get(ComponentAsCodeRmsIntegrationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("no RMS data: the full as-code view carries no RMS section")
    fun `full view has no RMS section when RMS has no data`() {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(false)
        val name = newComponent()

        val body = getAsCode(name)

        assertFalse(body.contains("RMS registered parameters"), body)
    }

    @Test
    @DisplayName("RMS data present: the full as-code view appends the labeled RMS section")
    fun `full view appends the RMS section when RMS has data`() {
        val name = newComponent()
        stubJavaActual(name, "[1,)", "21")

        val body = getAsCode(name)

        assertTrue(body.contains("// RMS registered parameters"), body)
        assertTrue(body.contains("javaVersion = \"21\""), body)
    }

    @Test
    @DisplayName("resolved view: an ACTUAL range covering the requested version wins over the configured value")
    fun `resolved view prefers the ACTUAL value when it covers the requested version`() {
        val name = newComponent(javaVersion = "8")
        stubJavaActual(name, "[1,)", "21")

        val body = getAsCode(name, version = "2.0")

        assertTrue(body.contains("javaVersion = \"21\""), body)
    }

    @Test
    @DisplayName("resolved view: no ACTUAL range covers the requested version, so today's configured value is unchanged")
    fun `resolved view falls back to the configured value when ACTUAL does not cover the requested version`() {
        val name = newComponent(javaVersion = "8")
        stubJavaActual(name, "[5,)", "21")

        val body = getAsCode(name, version = "2.0")

        assertTrue(body.contains("javaVersion = \"8\""), body)
        assertFalse(body.contains("javaVersion = \"21\""), body)
    }

    private fun stubJavaActual(
        name: String,
        range: String,
        value: String,
    ) {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(true)
        `when`(rmsBuildParametersService.currentReport()).thenReturn(
            RMSBuildParametersReport(
                generatedAt = Instant.now(),
                lastAttemptAt = Instant.now(),
                refreshError = null,
                components =
                    mapOf(
                        name to
                            ComponentBuildRanges(javaRanges = listOf(BuildRangeCollapser.Run(range, value)), mavenRanges = emptyList()),
                    ),
                unavailableComponents = emptySet(),
            ),
        )
    }

    private fun newComponent(
        name: String = "rms-ascode-${UUID.randomUUID().toString().take(8)}",
        javaVersion: String? = null,
    ): String {
        val build = if (javaVersion != null) """"buildSystem":"MAVEN","javaVersion":"$javaVersion"""" else """"buildSystem":"MAVEN""""
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"$name","componentOwner":"owner1",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{$build}}}""",
                    ),
            ).andExpect(status().is2xxSuccessful)
        return name
    }

    private fun getAsCode(
        name: String,
        version: String? = null,
    ): String {
        val path = "/rest/api/4/components/$name/as-code" + (version?.let { "?version=$it" } ?: "")
        return mvc
            .perform(get(path).with(adminJwt()))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
    }
}
