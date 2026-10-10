package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Pins the auth gate on
 * `rest/api/4/admin/teamcity-placement` end to end, through a real MockMvc dispatch (so
 * `@GetMapping(produces = ...)` content negotiation is actually exercised, unlike the plain-mock
 * [TeamcityPlacementControllerV4Test]). The permission model: running Diff, running Sync, and the Sync CSV all require
 * `IMPORT_DATA`; the Diff report (json/html/csv) needs only component read access.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class TeamcityPlacementControllerV4SecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var diffJobService: TeamcityPlacementDiffJobService

    @MockBean
    private lateinit var syncJobService: TeamcityPlacementSyncJobService

    @Autowired
    private lateinit var mvc: MockMvc

    private fun completedDiff(id: String = "D1") =
        TeamcityPlacementDiffJobState(
            id = id,
            state = JobState.COMPLETED,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            result = PlacementDiffResult(Instant.now(), emptyList()),
            errorMessage = null,
        )

    // ---- POST /diff: IMPORT_DATA ----

    @Test
    @DisplayName("anonymous POST /diff -> 401, job service not invoked")
    fun postDiffAnonymousReturns401() {
        mvc.perform(post("/rest/api/4/admin/teamcity-placement/diff")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT POST /diff -> 403 (component read only, no IMPORT_DATA)")
    fun postDiffViewerReturns403() {
        mvc.perform(post("/rest/api/4/admin/teamcity-placement/diff").with(viewerJwt())).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("admin JWT POST /diff -> 202")
    fun postDiffAdminReturns202() {
        `when`(diffJobService.startAsync("alice")).thenReturn(StartPlacementDiffResult(completedDiff(), isNewlyStarted = true))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-placement/diff").with(adminJwt()))
            .andExpect(status().isAccepted)
    }

    // ---- POST /sync: IMPORT_DATA ----

    @Test
    @DisplayName("anonymous POST /sync -> 401")
    fun postSyncAnonymousReturns401() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-placement/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT POST /sync -> 403 (component read only, no IMPORT_DATA)")
    fun postSyncViewerReturns403() {
        mvc
            .perform(
                post("/rest/api/4/admin/teamcity-placement/sync")
                    .with(viewerJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"diffId":"D1","componentIds":[]}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("admin JWT POST /sync with the current diffId -> 202")
    fun postSyncAdminReturns202() {
        val diff = completedDiff("D1")
        `when`(diffJobService.lastCompleted()).thenReturn(diff)
        val syncState = TeamcityPlacementSyncJobState(
            id = "S1",
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )
        `when`(syncJobService.startAsync(eq("alice"), eq(emptyList()), eq("D1"), any()))
            .thenReturn(StartPlacementSyncResult(syncState, isNewlyStarted = true))

        mvc
            .perform(
                post("/rest/api/4/admin/teamcity-placement/sync")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"diffId":"D1","componentIds":[]}"""),
            ).andExpect(status().isAccepted)
    }

    // ---- GET /sync/report.csv: IMPORT_DATA ----

    @Test
    @DisplayName("anonymous GET /sync/report.csv -> 401")
    fun getSyncReportCsvAnonymousReturns401() {
        mvc.perform(get("/rest/api/4/admin/teamcity-placement/sync/report.csv")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT GET /sync/report.csv -> 403 (component read only, no IMPORT_DATA)")
    fun getSyncReportCsvViewerReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/sync/report.csv").with(viewerJwt()))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("admin JWT GET /sync/report.csv -> 200, text/csv")
    fun getSyncReportCsvAdminReturns200() {
        `when`(syncJobService.current()).thenReturn(
            TeamcityPlacementSyncJobState(
                id = "S1",
                state = JobState.COMPLETED,
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                result = PlacementSyncResult("alice", 0, 0, 0, 0, emptyList()),
                errorMessage = null,
            ),
        )

        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/sync/report.csv").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv;charset=UTF-8")))
    }

    // ---- GET /diff/report.* : component read only, NOT gated by IMPORT_DATA ----

    @Test
    @DisplayName("anonymous GET /diff/report.json -> 401")
    fun getReportJsonAnonymousReturns401() {
        mvc.perform(get("/rest/api/4/admin/teamcity-placement/diff/report.json")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT GET /diff/report.json -> 200 (component read is enough)")
    fun getReportJsonViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/diff/report.json").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
    }

    @Test
    @DisplayName("viewer JWT GET /diff/report.html -> 200, text/html (component read is enough)")
    fun getReportHtmlViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/diff/report.html").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    @DisplayName("viewer JWT GET /diff/report.csv -> 200, text/csv (component read is enough)")
    fun getReportCsvViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/diff/report.csv").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv;charset=UTF-8")))
    }

    @Test
    @DisplayName("admin JWT GET /diff/report.json -> 200 (IMPORT_DATA is also enough)")
    fun getReportJsonAdminReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/diff/report.json").with(adminJwt()))
            .andExpect(status().isOk)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(TeamcityPlacementControllerV4SecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
