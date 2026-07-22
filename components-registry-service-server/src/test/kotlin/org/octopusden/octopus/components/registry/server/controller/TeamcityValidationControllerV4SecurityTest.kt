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
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityValidationSummaryResponse
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationQueryService
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
 * Pins the auth gate on the TeamCity validation dashboard read APIs
 * (`GET /admin/teamcity-validations` + `.../summary`) — added because the class-level
 * `@PreAuthorize("@permissionEvaluator.canImport()")` on `TeamcityValidationControllerV4` was
 * found commented out in the working tree during PR #443 review follow-up (an accidental local
 * debug leftover, not intentional). No regression test existed to catch that. This test pins:
 *   - WebSecurityConfig URL-level rule → 401 for anonymous callers.
 *   - TeamcityValidationControllerV4 class-level @PreAuthorize → 403 for authenticated callers
 *     without IMPORT_DATA.
 *   - 200 for an IMPORT_DATA-holding (admin) caller.
 *
 * TeamcityValidationQueryService is @MockBean'd so this test does not pull in the DB-backed
 * query implementation.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class TeamcityValidationControllerV4SecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var teamcityValidationQueryService: TeamcityValidationQueryService

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("SYS-092 anonymous GET /admin/teamcity-validations → 401, query service not invoked")
    fun SYS_092_listAnonymousReturns401() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-validations"))
            .andExpect(status().isUnauthorized)

        verify(teamcityValidationQueryService, never()).list(null, null, null)
    }

    @Test
    @DisplayName("SYS-092 editor JWT GET /admin/teamcity-validations → 403, query service not invoked")
    fun SYS_092_listEditorReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-validations").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcityValidationQueryService, never()).list(null, null, null)
    }

    @Test
    @DisplayName("SYS-092 admin JWT GET /admin/teamcity-validations → 200, query service invoked once")
    fun SYS_092_listAdminReturns200() {
        `when`(teamcityValidationQueryService.list(null, null, null)).thenReturn(emptyList())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-validations").with(adminJwt()))
            .andExpect(status().isOk)

        verify(teamcityValidationQueryService, times(1)).list(null, null, null)
    }

    @Test
    @DisplayName("SYS-092 anonymous GET /admin/teamcity-validations/summary → 401, query service not invoked")
    fun SYS_092_summaryAnonymousReturns401() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-validations/summary"))
            .andExpect(status().isUnauthorized)

        verify(teamcityValidationQueryService, never()).summary()
    }

    @Test
    @DisplayName("SYS-092 editor JWT GET /admin/teamcity-validations/summary → 403, query service not invoked")
    fun SYS_092_summaryEditorReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-validations/summary").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcityValidationQueryService, never()).summary()
    }

    @Test
    @DisplayName("SYS-092 admin JWT GET /admin/teamcity-validations/summary → 200, query service invoked once")
    fun SYS_092_summaryAdminReturns200() {
        `when`(teamcityValidationQueryService.summary()).thenReturn(
            TeamcityValidationSummaryResponse(componentsWithIssues = 0, findings = 0, byType = emptyMap(), byStatus = emptyMap()),
        )

        mvc
            .perform(get("/rest/api/4/admin/teamcity-validations/summary").with(adminJwt()))
            .andExpect(status().isOk)

        verify(teamcityValidationQueryService, times(1)).summary()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(TeamcityValidationControllerV4SecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
