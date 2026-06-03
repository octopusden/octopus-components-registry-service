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
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.teamcity.StartTeamcitySyncResult
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncJobState
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncResult
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
 * Pins the auth + response-shape contract of the TeamCity resync endpoints
 * (`POST /sync` + `GET /sync/job`).
 *
 * Both gates that protect the endpoints are exercised:
 *   - WebSecurityConfig URL-level rule → 401 for anonymous callers.
 *   - AdminControllerV4 class-level @PreAuthorize("@permissionEvaluator.canImport()")
 *     → 403 for authenticated callers without IMPORT_DATA.
 *
 * TeamcitySyncJobService is @MockBean'd so the test does not pull in the
 * TeamcityClient bean wiring (which would attempt outbound HTTP if base-url
 * were ever set). Asserts cover 202/409 attach + 409 cross-kind envelope on
 * POST, and 200/404 polling on GET, including the snake_case
 * `skipped_no_match` / `skipped_ambiguous` / `ambiguous_auto_resolved` keys in
 * the COMPLETED `result`.
 *
 * Other migration job services are @MockBean'd to keep this test scoped to
 * the resync paths — same convention as AdminControllerV4SecurityTest.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class TeamcityResyncControllerTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var teamcitySyncJobService: TeamcitySyncJobService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var migrationJobService: org.octopusden.octopus.components.registry.server.service.MigrationJobService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var historyMigrationJobService: org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService

    @Autowired
    private lateinit var mvc: MockMvc

    // ---------------------------------------------------------------------
    // POST /sync — 202 newly-started, 409 same-kind attach, 409 cross-kind.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("anonymous POST /admin/teamcity-project-ids/sync → 401, startAsync not invoked")
    fun syncAnonymousReturns401() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/sync"))
            .andExpect(status().isUnauthorized)

        verify(teamcitySyncJobService, never()).startAsync()
    }

    @Test
    @DisplayName("editor JWT POST /admin/teamcity-project-ids/sync → 403, startAsync not invoked")
    fun syncEditorReturns403() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/sync").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcitySyncJobService, never()).startAsync()
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-project-ids/sync newly-started → 202 with kind=job body")
    fun syncAdminFreshReturns202() {
        `when`(teamcitySyncJobService.startAsync())
            .thenReturn(StartTeamcitySyncResult(runningState("job-1"), isNewlyStarted = true))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/sync").with(adminJwt()))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value("job-1"))
            .andExpect(jsonPath("$.state").value(JobState.RUNNING.name))
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.result").doesNotExist())

        verify(teamcitySyncJobService, times(1)).startAsync()
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-project-ids/sync same-kind RUNNING → 409 with same job body")
    fun syncAdminSameKindAttachReturns409() {
        `when`(teamcitySyncJobService.startAsync())
            .thenReturn(StartTeamcitySyncResult(runningState("job-existing"), isNewlyStarted = false))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/sync").with(adminJwt()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.id").value("job-existing"))
            .andExpect(jsonPath("$.state").value(JobState.RUNNING.name))
            .andExpect(jsonPath("$.kind").value("job"))
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-project-ids/sync cross-kind COMPONENTS → 409 with conflict envelope")
    fun syncAdminCrossKindComponentsReturns409Conflict() {
        `when`(teamcitySyncJobService.startAsync())
            .thenThrow(
                MigrationConflictException(
                    MigrationLifecycleGate.ActiveJob(MigrationLifecycleGate.JobKind.COMPONENTS, "components-1"),
                ),
            )

        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/sync").with(adminJwt()))
            .andExpect(status().isConflict)
            // kind="conflict" is the discriminator the SPA's parseSameKindAttach uses to
            // distinguish a cross-kind envelope from a same-kind job body. Pin it here
            // alongside the structural fields.
            .andExpect(jsonPath("$.kind").value("conflict"))
            .andExpect(jsonPath("$.code").value("components-migration-running"))
            .andExpect(jsonPath("$.activeKind").value("COMPONENTS"))
            .andExpect(jsonPath("$.activeJobId").value("components-1"))
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-project-ids/sync cross-kind HISTORY → 409 with conflict envelope")
    fun syncAdminCrossKindHistoryReturns409Conflict() {
        `when`(teamcitySyncJobService.startAsync())
            .thenThrow(
                MigrationConflictException(
                    MigrationLifecycleGate.ActiveJob(MigrationLifecycleGate.JobKind.HISTORY, "history-1"),
                ),
            )

        mvc
            .perform(post("/rest/api/4/admin/teamcity-project-ids/sync").with(adminJwt()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.kind").value("conflict"))
            .andExpect(jsonPath("$.code").value("history-migration-running"))
            .andExpect(jsonPath("$.activeKind").value("HISTORY"))
            .andExpect(jsonPath("$.activeJobId").value("history-1"))
    }

    // ---------------------------------------------------------------------
    // GET /sync/job — 200 when state is present, 404 when idle.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("anonymous GET /admin/teamcity-project-ids/sync/job → 401")
    fun getJobAnonymousReturns401() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-project-ids/sync/job"))
            .andExpect(status().isUnauthorized)

        verify(teamcitySyncJobService, never()).current()
    }

    @Test
    @DisplayName("editor GET /admin/teamcity-project-ids/sync/job → 403")
    fun getJobEditorReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-project-ids/sync/job").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcitySyncJobService, never()).current()
    }

    @Test
    @DisplayName("admin GET /admin/teamcity-project-ids/sync/job idle → 404")
    fun getJobIdleReturns404() {
        `when`(teamcitySyncJobService.current()).thenReturn(null)

        mvc
            .perform(get("/rest/api/4/admin/teamcity-project-ids/sync/job").with(adminJwt()))
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("admin GET /admin/teamcity-project-ids/sync/job FAILED → 200 with errorMessage, no result")
    fun getJobFailedReturns200WithErrorMessage() {
        `when`(teamcitySyncJobService.current())
            .thenReturn(
                TeamcitySyncJobState(
                    id = "job-failed",
                    state = JobState.FAILED,
                    startedAt = Instant.parse("2026-05-06T10:00:00Z"),
                    finishedAt = Instant.parse("2026-05-06T10:00:42Z"),
                    result = null,
                    errorMessage = "TC unavailable",
                ),
            )

        mvc
            .perform(get("/rest/api/4/admin/teamcity-project-ids/sync/job").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("job-failed"))
            .andExpect(jsonPath("$.state").value(JobState.FAILED.name))
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.errorMessage").value("TC unavailable"))
            .andExpect(jsonPath("$.result").doesNotExist())
    }

    @Test
    @DisplayName("admin GET /admin/teamcity-project-ids/sync/job COMPLETED → 200 with result counts")
    fun getJobCompletedReturns200WithResult() {
        val result =
            TeamcitySyncResult(
                scanned = 5,
                updated = 2,
                unchanged = 3,
                skippedNoMatch = 0,
                skippedAmbiguous = 0,
                ambiguousAutoResolved = 0,
                errors = emptyList(),
            )
        `when`(teamcitySyncJobService.current())
            .thenReturn(
                TeamcitySyncJobState(
                    id = "job-3",
                    state = JobState.COMPLETED,
                    startedAt = Instant.parse("2026-05-06T10:00:00Z"),
                    finishedAt = Instant.parse("2026-05-06T10:00:42Z"),
                    result = result,
                    errorMessage = null,
                ),
            )

        mvc
            .perform(get("/rest/api/4/admin/teamcity-project-ids/sync/job").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("job-3"))
            .andExpect(jsonPath("$.state").value(JobState.COMPLETED.name))
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.result.scanned").value(5))
            .andExpect(jsonPath("$.result.updated").value(2))
            .andExpect(jsonPath("$.result.skipped_no_match").value(0))
            .andExpect(jsonPath("$.result.skipped_ambiguous").value(0))
            .andExpect(jsonPath("$.result.ambiguous_auto_resolved").value(0))
    }

    private fun runningState(jobId: String) =
        TeamcitySyncJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.parse("2026-05-06T10:00:00Z"),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

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
