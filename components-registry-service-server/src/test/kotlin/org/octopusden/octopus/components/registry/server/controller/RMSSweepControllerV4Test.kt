package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.rms.ComponentBuildRanges
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersReport
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersService
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

/**
 * Wiring test for `GET /admin/rms-sweep`: asserts the real JSON shape produced from a populated
 * [RMSBuildParametersService], including the disabled/empty path. Permission gating itself is
 * pinned separately by [RMSSweepControllerV4SecurityTest].
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class RMSSweepControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var rmsBuildParametersService: RMSBuildParametersService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("enabled, with data -> reports counts, sorted unavailable list, and duration")
    fun enabledWithDataReportsStatus() {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(true)
        `when`(rmsBuildParametersService.currentReport()).thenReturn(
            RMSBuildParametersReport(
                generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                lastAttemptAt = Instant.parse("2026-01-01T00:00:00Z"),
                refreshError = null,
                components = mapOf("comp-a" to ComponentBuildRanges(emptyList(), emptyList())),
                unavailableComponents = setOf("comp-z", "comp-a"),
                lastSweepDuration = Duration.ofMillis(500),
            ),
        )

        val body =
            mvc
                .perform(get("/rest/api/4/admin/rms-sweep").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        assertTrue(json["enabled"].asBoolean())
        assertEquals(1, json["componentsWithData"].asInt())
        assertEquals(500L, json["lastSweepDurationMillis"].asLong())
        assertTrue(json["refreshError"].isNull)
        assertEquals(listOf("comp-a", "comp-z"), json["unavailableComponents"].map { it.asText() })
    }

    @Test
    @DisplayName("disabled -> enabled=false, empty data")
    fun disabledReportsEmptyStatus() {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(false)
        `when`(rmsBuildParametersService.currentReport())
            .thenReturn(RMSBuildParametersReport(null, null, null, emptyMap(), emptySet()))

        val body =
            mvc
                .perform(get("/rest/api/4/admin/rms-sweep").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        assertFalse(json["enabled"].asBoolean())
        assertEquals(0, json["componentsWithData"].asInt())
        assertTrue(json["unavailableComponents"].isEmpty)
        assertTrue(json["lastSweepDurationMillis"].isNull)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(RMSSweepControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
