package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersReport
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersService
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
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

/**
 * Pins the auth gate on `GET /admin/rms-sweep`, mirroring
 * [TeamcityValidationControllerV4SecurityTest]:
 *   - WebSecurityConfig URL-level rule -> 401 for anonymous callers.
 *   - RMSSweepControllerV4 class-level @PreAuthorize -> 403 for authenticated callers without
 *     IMPORT_DATA.
 *   - 200 for an IMPORT_DATA-holding (admin) caller.
 *
 * RMSBuildParametersService is @MockBean'd so this test does not depend on a real sweep having run.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class RMSSweepControllerV4SecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var rmsBuildParametersService: RMSBuildParametersService

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("anonymous GET /admin/rms-sweep -> 401, service not invoked")
    fun anonymousReturns401() {
        mvc
            .perform(get("/rest/api/4/admin/rms-sweep"))
            .andExpect(status().isUnauthorized)

        verify(rmsBuildParametersService, never()).currentReport()
    }

    @Test
    @DisplayName("editor JWT GET /admin/rms-sweep -> 403, service not invoked")
    fun editorReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/rms-sweep").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(rmsBuildParametersService, never()).currentReport()
    }

    @Test
    @DisplayName("admin JWT GET /admin/rms-sweep -> 200, service invoked once")
    fun adminReturns200() {
        `when`(rmsBuildParametersService.isEnabled()).thenReturn(false)
        `when`(rmsBuildParametersService.currentReport())
            .thenReturn(RMSBuildParametersReport(null, null, null, emptyMap(), emptySet()))

        mvc
            .perform(get("/rest/api/4/admin/rms-sweep").with(adminJwt()))
            .andExpect(status().isOk)

        verify(rmsBuildParametersService, times(1)).currentReport()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(RMSSweepControllerV4SecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
