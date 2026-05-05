package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncResult
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pins the auth + response-shape contract of POST /rest/api/4/admin/teamcity-project-ids/resync.
 *
 * Both gates that protect the endpoint are exercised:
 *   - WebSecurityConfig URL-level rule → 401 for anonymous callers.
 *   - AdminControllerV4 class-level @PreAuthorize("@permissionEvaluator.canImport()")
 *     → 403 for authenticated callers without IMPORT_DATA.
 *
 * TeamcitySyncService is @MockBean'd so the test does not pull in the
 * TeamcityClient bean wiring (which would attempt outbound HTTP if base-url
 * were ever set). The positive path verifies the 6-field counts shape
 * including the snake_case `skipped_no_match` / `skipped_ambiguous` keys.
 *
 * Other migration job services are @MockBean'd to keep this test scoped
 * to the resync path — same convention as AdminControllerV4SecurityTest.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
class TeamcityResyncControllerTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var teamcitySyncService: TeamcitySyncService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var migrationJobService: org.octopusden.octopus.components.registry.server.service.MigrationJobService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var historyMigrationJobService: org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("anonymous POST /admin/teamcity-project-ids/resync → 401, sync not invoked")
    fun anonymous_returns401() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/resync"))
            .andExpect(status().isUnauthorized)

        verify(teamcitySyncService, never()).resync()
    }

    @Test
    @DisplayName("editor JWT POST /admin/teamcity-project-ids/resync → 403, sync not invoked")
    fun editor_returns403() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/resync").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcitySyncService, never()).resync()
    }

    @Test
    @DisplayName("admin JWT → 200 with full counts shape (incl. snake_case skipped_* keys)")
    fun admin_returns200WithCounts() {
        `when`(teamcitySyncService.resync()).thenReturn(
            TeamcitySyncResult(
                scanned = 12,
                updated = 3,
                unchanged = 7,
                skippedNoMatch = 1,
                skippedAmbiguous = 1,
                errors = listOf("oops on alpha"),
            ),
        )

        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/resync").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scanned").value(12))
            .andExpect(jsonPath("$.updated").value(3))
            .andExpect(jsonPath("$.unchanged").value(7))
            .andExpect(jsonPath("$.skipped_no_match").value(1))
            .andExpect(jsonPath("$.skipped_ambiguous").value(1))
            .andExpect(jsonPath("$.errors[0]").value("oops on alpha"))

        verify(teamcitySyncService, times(1)).resync()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(TeamcityResyncControllerTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
