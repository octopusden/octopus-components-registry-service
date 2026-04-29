package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationJobState
import org.octopusden.octopus.components.registry.server.service.StartMigrationResult
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Pin the new async-migration endpoint contract on the controller layer:
 *
 * - POST /admin/migrate first call → 202 + JobResponse (RUNNING).
 * - POST /admin/migrate while a previous job is still RUNNING → 409 + same JobResponse,
 *   so the SPA "attaches" to the in-flight job rather than spawning a duplicate.
 * - GET /admin/migrate/job before any start → 404.
 * - GET /admin/migrate/job after start → 200 + the latest JobResponse with
 *   per-component counters.
 *
 * Runs with @MockBean MigrationJobService — the service-layer idempotency is
 * pinned separately by MigrationJobServiceImplTest.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
class AdminMigrateJobControllerTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var migrationJobService: MigrationJobService

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun `POST migrate first call returns 202 with the freshly-started job body`() {
        `when`(migrationJobService.startAsync()).thenReturn(
            StartMigrationResult(state = RUNNING_STATE, isNewlyStarted = true),
        )

        mvc
            .perform(post("/rest/api/4/admin/migrate").with(adminJwt()))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value(RUNNING_STATE.id))
            .andExpect(jsonPath("$.state").value("RUNNING"))
    }

    @Test
    fun `POST migrate while RUNNING returns 409 with the same job id (idempotency gate)`() {
        // Service signals "already running" via isNewlyStarted=false; the controller
        // maps that to 409 Conflict. The body is the existing job state — same id —
        // so the SPA can "attach" and start polling.
        `when`(migrationJobService.startAsync()).thenReturn(
            StartMigrationResult(state = RUNNING_STATE, isNewlyStarted = false),
        )

        mvc
            .perform(post("/rest/api/4/admin/migrate").with(adminJwt()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.id").value(RUNNING_STATE.id))
            .andExpect(jsonPath("$.state").value("RUNNING"))
    }

    @Test
    fun `GET migrate-job returns 404 when no job has been started since pod boot`() {
        `when`(migrationJobService.current()).thenReturn(null)

        mvc
            .perform(get("/rest/api/4/admin/migrate/job").with(adminJwt()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET migrate-job returns 200 with the current job and per-component counters`() {
        val inFlight =
            RUNNING_STATE.copy(
                currentComponent = "comp-247",
                migrated = 200,
                failed = 5,
                skipped = 2,
                total = 936,
            )
        `when`(migrationJobService.current()).thenReturn(inFlight)

        mvc
            .perform(get("/rest/api/4/admin/migrate/job").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(inFlight.id))
            .andExpect(jsonPath("$.state").value("RUNNING"))
            .andExpect(jsonPath("$.currentComponent").value("comp-247"))
            .andExpect(jsonPath("$.migrated").value(200))
            .andExpect(jsonPath("$.failed").value(5))
            .andExpect(jsonPath("$.skipped").value(2))
            .andExpect(jsonPath("$.total").value(936))
    }

    companion object {
        private val RUNNING_STATE =
            MigrationJobState(
                id = "11111111-2222-3333-4444-555555555555",
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
            )

        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(AdminMigrateJobControllerTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
