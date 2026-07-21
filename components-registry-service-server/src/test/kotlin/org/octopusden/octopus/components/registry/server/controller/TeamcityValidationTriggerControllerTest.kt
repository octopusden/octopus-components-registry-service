package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
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
import org.octopusden.octopus.components.registry.server.teamcity.validation.StartTeamcityValidationResult
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationJobService
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationJobState
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
 * Pins the auth + response-shape contract of the TeamCity validation trigger endpoints
 * (`POST /admin/teamcity-validation` + `GET /admin/teamcity-validation/job`) — the "202/409 API
 * contract" flagged as untested in PR #443 review (pgorbachev, P2): the runtime behavior is
 * 202 Accepted for a newly-started job and 409 Conflict when attaching to an already-running one
 * (same-kind) or hitting a cross-kind migration conflict, mirroring `TeamcityResyncControllerTest`
 * for the sibling `/teamcity-project-ids/sync` endpoint.
 *
 * TeamcityValidationJobService is @MockBean'd so this test does not pull in the real validation
 * pipeline (TeamCity client, DB repositories). Other job services are @MockBean'd to keep this
 * test scoped to the validation-trigger paths — same convention as AdminControllerV4SecurityTest
 * and TeamcityResyncControllerTest.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class TeamcityValidationTriggerControllerTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var teamcityValidationJobService: TeamcityValidationJobService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var teamcitySyncJobService: org.octopusden.octopus.components.registry.server.teamcity.sync.TeamcitySyncJobService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var migrationJobService: org.octopusden.octopus.components.registry.server.service.MigrationJobService

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var historyMigrationJobService: org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService

    @Autowired
    private lateinit var mvc: MockMvc

    // ---------------------------------------------------------------------
    // POST /teamcity-validation — 202 newly-started, 409 same-kind attach, 409 cross-kind.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("anonymous POST /admin/teamcity-validation → 401, startAsync not invoked")
    fun postAnonymousReturns401() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-validation"))
            .andExpect(status().isUnauthorized)

        verify(teamcityValidationJobService, never()).startAsync(anyString())
    }

    @Test
    @DisplayName("editor JWT POST /admin/teamcity-validation → 403, startAsync not invoked")
    fun postEditorReturns403() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-validation").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcityValidationJobService, never()).startAsync(anyString())
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-validation newly-started → 202 with kind=job body")
    fun postAdminFreshReturns202() {
        `when`(teamcityValidationJobService.startAsync(anyString()))
            .thenReturn(StartTeamcityValidationResult(runningState("job-1"), isNewlyStarted = true))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-validation").with(adminJwt()))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value("job-1"))
            .andExpect(jsonPath("$.state").value(JobState.RUNNING.name))
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.result").doesNotExist())

        verify(teamcityValidationJobService, times(1)).startAsync(anyString())
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-validation same-kind RUNNING → 409 with same job body (attach)")
    fun postAdminSameKindAttachReturns409() {
        `when`(teamcityValidationJobService.startAsync(anyString()))
            .thenReturn(StartTeamcityValidationResult(runningState("job-existing"), isNewlyStarted = false))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-validation").with(adminJwt()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.id").value("job-existing"))
            .andExpect(jsonPath("$.state").value(JobState.RUNNING.name))
            .andExpect(jsonPath("$.kind").value("job"))

        verify(teamcityValidationJobService, times(1)).startAsync(anyString())
    }

    @Test
    @DisplayName("admin POST /admin/teamcity-validation cross-kind COMPONENTS → 409 with conflict envelope")
    fun postAdminCrossKindComponentsReturns409Conflict() {
        `when`(teamcityValidationJobService.startAsync(anyString()))
            .thenThrow(
                MigrationConflictException(
                    MigrationLifecycleGate.ActiveJob(MigrationLifecycleGate.JobKind.COMPONENTS, "components-1"),
                ),
            )

        mvc
            .perform(post("/rest/api/4/admin/teamcity-validation").with(adminJwt()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.kind").value("conflict"))
            .andExpect(jsonPath("$.code").value("components-migration-running"))
            .andExpect(jsonPath("$.activeKind").value("COMPONENTS"))
            .andExpect(jsonPath("$.activeJobId").value("components-1"))
    }

    // ---------------------------------------------------------------------
    // GET /teamcity-validation/job — 200 when state is present, 404 when idle.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("anonymous GET /admin/teamcity-validation/job → 401")
    fun getJobAnonymousReturns401() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-validation/job"))
            .andExpect(status().isUnauthorized)

        verify(teamcityValidationJobService, never()).current()
    }

    @Test
    @DisplayName("editor GET /admin/teamcity-validation/job → 403")
    fun getJobEditorReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-validation/job").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(teamcityValidationJobService, never()).current()
    }

    @Test
    @DisplayName("admin GET /admin/teamcity-validation/job idle → 404")
    fun getJobIdleReturns404() {
        `when`(teamcityValidationJobService.current()).thenReturn(null)

        mvc
            .perform(get("/rest/api/4/admin/teamcity-validation/job").with(adminJwt()))
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("admin GET /admin/teamcity-validation/job COMPLETED → 200 with result counts")
    fun getJobCompletedReturns200WithResult() {
        val result =
            org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationResult(
                scanned = 5,
                succeeded = 4,
                failed = 1,
                projectsWithIssues = 2,
                removed = 1,
                errors = listOf("Project 'X' not returned by TeamCity; kept previous findings"),
            )
        `when`(teamcityValidationJobService.current())
            .thenReturn(
                TeamcityValidationJobState(
                    id = "job-3",
                    state = JobState.COMPLETED,
                    startedAt = Instant.parse("2026-07-21T10:00:00Z"),
                    finishedAt = Instant.parse("2026-07-21T10:00:42Z"),
                    result = result,
                    errorMessage = null,
                ),
            )

        mvc
            .perform(get("/rest/api/4/admin/teamcity-validation/job").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("job-3"))
            .andExpect(jsonPath("$.state").value(JobState.COMPLETED.name))
            .andExpect(jsonPath("$.kind").value("job"))
            .andExpect(jsonPath("$.result.scanned").value(5))
            .andExpect(jsonPath("$.result.succeeded").value(4))
            .andExpect(jsonPath("$.result.failed").value(1))
            .andExpect(jsonPath("$.result.removed").value(1))
    }

    private fun runningState(jobId: String) =
        TeamcityValidationJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.parse("2026-07-21T10:00:00Z"),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(TeamcityValidationTriggerControllerTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
