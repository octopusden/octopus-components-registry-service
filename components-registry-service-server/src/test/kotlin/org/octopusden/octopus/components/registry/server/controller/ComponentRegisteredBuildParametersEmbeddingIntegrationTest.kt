package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Proves `ComponentManagementServiceImpl.attachRegisteredBuildParameters` is really wired into the
 * live `GET /components/{id}` response — `RegisteredBuildParametersMapper.detailFor`'s own logic is
 * already covered in isolation, but nothing previously exercised the real call-site. Uses the H2
 * `ft-db` profile with a mocked `RMSBuildParametersService`, so it runs without Docker.
 *
 * Also the regression test for "disabled shows nothing, not 'unavailable'": a disabled integration
 * and an enabled-but-never-swept one both leave the report empty, so only checking `isEnabled()`
 * first tells them apart.
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
class ComponentRegisteredBuildParametersEmbeddingIntegrationTest {
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

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentRegisteredBuildParametersEmbeddingIntegrationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("a disabled RMS integration carries no registeredBuildParameters field at all — not even 'unavailable'")
    fun `disabled shows nothing, not unavailable`() {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(false)
        val id = newComponent()

        val registeredBuildParameters = getComponent(id)["registeredBuildParameters"]
        assertTrue(
            registeredBuildParameters == null || registeredBuildParameters.isNull,
            "a disabled integration must omit registeredBuildParameters entirely, got: $registeredBuildParameters",
        )
    }

    @Test
    @DisplayName("enabled but never successfully swept: registeredBuildParameters is marked unavailable")
    fun `enabled with no sweep data shows unavailable`() {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(true)
        `when`(rmsBuildParametersService.currentReport())
            .thenReturn(RMSBuildParametersReport(null, null, null, emptyMap(), emptySet()))
        val id = newComponent()

        val registeredBuildParameters = getComponent(id)["registeredBuildParameters"]
        assertTrue(registeredBuildParameters.get("actualDataUnavailable").asBoolean())
    }

    @Test
    @DisplayName("enabled with swept data: the real ACTUAL ranges are attached to the detail response")
    fun `enabled with sweep data attaches real ranges`() {
        val name = "rms-embed-${UUID.randomUUID().toString().take(8)}"
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(true)
        `when`(rmsBuildParametersService.currentReport()).thenReturn(
            RMSBuildParametersReport(
                generatedAt = Instant.now(),
                lastAttemptAt = Instant.now(),
                refreshError = null,
                components = mapOf(
                    name to ComponentBuildRanges(javaRanges = listOf(BuildRangeCollapser.Run("[1,)", "11")), mavenRanges = emptyList()),
                ),
                unavailableComponents = emptySet(),
            ),
        )
        val id = newComponent(name)

        val registeredBuildParameters = getComponent(id)["registeredBuildParameters"]
        assertEquals(false, registeredBuildParameters.get("actualDataUnavailable").asBoolean())
        assertEquals(
            "11",
            registeredBuildParameters
                .get("javaActualRanges")
                .get(0)
                .get("value")
                .asText(),
        )
    }

    private fun newComponent(name: String = "rms-embed-${UUID.randomUUID().toString().take(8)}"): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","componentOwner":"owner1",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                        ),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun getComponent(componentId: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$componentId").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }
}
