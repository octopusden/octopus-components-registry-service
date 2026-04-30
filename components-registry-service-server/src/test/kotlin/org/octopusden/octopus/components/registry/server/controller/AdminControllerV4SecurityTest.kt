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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

// MIG-024: pin both gates that protect POST /rest/api/4/admin/migrate:
//   - URL-level requestMatchers("/rest/api/4/**").authenticated() in
//     WebSecurityConfig → 401 for anonymous callers.
//   - Class-level @PreAuthorize("@permissionEvaluator.canImport()") on
//     AdminControllerV4 → 403 for authenticated callers without IMPORT_DATA.
//
// MigrationJobService is mocked so the positive path doesn't actually
// kick a background migration in the test context. Slice tests
// (@WebMvcTest) wouldn't load the real WebSecurityConfig, so the only
// meaningful regression test is a full @SpringBootTest.
//
// The endpoint contract changed in this PR: it now returns 202 Accepted
// with a JobResponse instead of 200 OK with FullMigrationResult.
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
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
