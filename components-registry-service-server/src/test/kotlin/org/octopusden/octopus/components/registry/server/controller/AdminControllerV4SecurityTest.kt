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
import org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationJobState
import org.octopusden.octopus.components.registry.server.service.MigrationPhase
import org.octopusden.octopus.components.registry.server.service.StartMigrationResult
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

// Pins the two gates that protect AdminControllerV4's endpoints, exercised through
// the full security stack (a slice @WebMvcTest wouldn't load the real WebSecurityConfig):
//   - URL-level requestMatchers("/rest/api/4/**").authenticated() in
//     WebSecurityConfig → 401 for anonymous callers.
//   - Class-level @PreAuthorize("@permissionEvaluator.canImport()") on
//     AdminControllerV4 → 403 for authenticated callers without IMPORT_DATA.
//
// Two surfaces are covered:
//   - MIG-024: POST /admin/migrate (returns 202 Accepted with a JobResponse).
//     MigrationJobService is mocked so the positive path doesn't kick a real
//     background migration in the test context.
//   - #250: POST /admin/reload-config — the config-write path since #343 made
//     field-config/component-defaults code-as-config (the legacy ConfigControllerV4
//     PUTs are now 410-Gone tombstones). ContextRefresher is mocked so the positive
//     path doesn't trigger a real Spring Cloud Config refresh.
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class AdminControllerV4SecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var migrationJobService: MigrationJobService

    /**
     * Mocked too — the controller constructor wires it in for the new
     * /migrate-history endpoints. The MIG-024 cases below don't drive it,
     * but without the @MockBean Spring would try to wire the real
     * HistoryMigrationJobServiceImpl which pulls in the DB layer and
     * defeats this test's "no integration" stance.
     */
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var historyMigrationJobService: HistoryMigrationJobService

    /**
     * Mocked so the controller's TC-resync collaborator is satisfied without
     * standing up the real TC client. Same rationale as the migration-job
     * mocks above: keep this test purely about the security gates on
     * AdminControllerV4.
     */
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var teamcitySyncJobService:
        org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncJobService

    /**
     * Mocked so the `/reload-config` positive path does not trigger a real Spring Cloud
     * Config refresh (which would re-fetch the profile and rebind AdminConfigProperties)
     * in the test context. The 401/403 cases never reach the controller body — method
     * security denies first — so `refresh()` must NOT be invoked there.
     */
    @MockBean
    private lateinit var contextRefresher: ContextRefresher

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("MIG-024: POST /admin/migrate without JWT returns 401 and does not invoke MigrationJobService")
    fun `MIG-024 anonymous POST migrate returns 401 and does not start a job`() {
        mvc
            .perform(post("/rest/api/4/admin/migrate"))
            .andExpect(status().isUnauthorized)

        verify(migrationJobService, never()).startAsync()
    }

    @Test
    @DisplayName("MIG-024: POST /admin/migrate with non-IMPORT_DATA JWT returns 403 and does not invoke MigrationJobService")
    fun `MIG-024 editor JWT POST migrate returns 403 and does not start a job`() {
        mvc
            .perform(post("/rest/api/4/admin/migrate").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(migrationJobService, never()).startAsync()
    }

    @Test
    @DisplayName("MIG-024: POST /admin/migrate with IMPORT_DATA JWT returns 202 and invokes MigrationJobService.startAsync exactly once")
    fun `MIG-024 admin JWT POST migrate returns 202 and starts the job`() {
        `when`(migrationJobService.startAsync()).thenReturn(
            StartMigrationResult(state = RUNNING_STATE, isNewlyStarted = true),
        )

        mvc
            .perform(post("/rest/api/4/admin/migrate").with(adminJwt()))
            .andExpect(status().isAccepted)

        verify(migrationJobService, times(1)).startAsync()
    }

    // #250: config-write guard. Since #343, field-config / component-defaults are
    // code-as-config — the legacy ConfigControllerV4 PUTs are 410-Gone tombstones and
    // the real write path is POST /admin/reload-config (re-reads service-config). It
    // inherits AdminControllerV4's class-level @PreAuthorize("@permissionEvaluator.canImport()").
    // These cases pin that the config-write capability stays IMPORT_DATA-gated so a
    // later refactor can't silently move it off the admin gate.
    @Test
    @DisplayName("config-write: POST /admin/reload-config without JWT returns 401 and does not refresh")
    fun `reload-config anonymous returns 401 and does not refresh`() {
        mvc
            .perform(post("/rest/api/4/admin/reload-config"))
            .andExpect(status().isUnauthorized)

        verify(contextRefresher, never()).refresh()
    }

    @Test
    @DisplayName("config-write: POST /admin/reload-config with non-IMPORT_DATA JWT returns 403 and does not refresh")
    fun `reload-config editor JWT returns 403 and does not refresh`() {
        mvc
            .perform(post("/rest/api/4/admin/reload-config").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(contextRefresher, never()).refresh()
    }

    @Test
    @DisplayName("config-write: POST /admin/reload-config with IMPORT_DATA JWT returns 200 and refreshes exactly once")
    fun `reload-config admin JWT returns 200 and refreshes`() {
        `when`(contextRefresher.refresh()).thenReturn(emptySet())

        mvc
            .perform(post("/rest/api/4/admin/reload-config").with(adminJwt()))
            .andExpect(status().isOk)

        verify(contextRefresher, times(1)).refresh()
    }

    companion object {
        private val RUNNING_STATE =
            MigrationJobState(
                id = "00000000-0000-0000-0000-000000000000",
                state = JobState.RUNNING,
                startedAt = Instant.parse("2026-04-29T10:00:00Z"),
                finishedAt = null,
                total = 0,
                migrated = 0,
                failed = 0,
                skipped = 0,
                currentComponent = null,
                errorMessage = null,
                result = null,
                phase = MigrationPhase.DEFAULTS,
            )

        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(AdminControllerV4SecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
