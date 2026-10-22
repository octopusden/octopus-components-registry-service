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
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffStaleException
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.StartCheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.StartCheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobState
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Pins the auth gate on
 * `rest/api/4/admin/teamcity-checkout-layout` end to end, through a real MockMvc dispatch (so
 * `@GetMapping(produces = ...)` content negotiation is actually exercised, unlike the plain-mock
 * [TeamcityCheckoutLayoutControllerV4Test]). The permission model: running Diff, running Sync, and the Sync CSV all require
 * `IMPORT_DATA`; the Diff report (json/html/csv) needs only component read access.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class TeamcityCheckoutLayoutControllerV4SecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var diffJobService: TeamcityCheckoutLayoutDiffJobService

    @MockBean
    private lateinit var syncJobService: TeamcityCheckoutLayoutSyncJobService

    @Autowired
    private lateinit var mvc: MockMvc

    private fun completedDiff(id: String = "D1") =
        TeamcityCheckoutLayoutDiffJobState(
            id = id,
            state = JobState.COMPLETED,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            result = CheckoutLayoutDiffResult(Instant.now(), emptyList()),
            errorMessage = null,
        )

    // ---- POST /diff: IMPORT_DATA ----

    @Test
    @DisplayName("anonymous POST /diff -> 401, job service not invoked")
    fun postDiffAnonymousReturns401() {
        mvc.perform(post("/rest/api/4/admin/teamcity-checkout-layout/diff")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT POST /diff -> 403 (component read only, no IMPORT_DATA)")
    fun postDiffViewerReturns403() {
        mvc.perform(post("/rest/api/4/admin/teamcity-checkout-layout/diff").with(viewerJwt())).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("admin JWT POST /diff -> 202")
    fun postDiffAdminReturns202() {
        `when`(diffJobService.startAsync("alice")).thenReturn(StartCheckoutLayoutDiffResult(completedDiff(), isNewlyStarted = true))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-checkout-layout/diff").with(adminJwt()))
            .andExpect(status().isAccepted)
    }

    // ---- POST /sync: IMPORT_DATA ----

    @Test
    @DisplayName("anonymous POST /sync -> 401")
    fun postSyncAnonymousReturns401() {
        mvc
            .perform(post("/rest/api/4/admin/teamcity-checkout-layout/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT POST /sync -> 403 (component read only, no IMPORT_DATA)")
    fun postSyncViewerReturns403() {
        mvc
            .perform(
                post("/rest/api/4/admin/teamcity-checkout-layout/sync")
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
        val syncState = TeamcityCheckoutLayoutSyncJobState(
            id = "S1",
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )
        `when`(syncJobService.startAsync(eq("alice"), eq(emptyList()), eq("D1"), any()))
            .thenReturn(StartCheckoutLayoutSyncResult(syncState, isNewlyStarted = true))

        mvc
            .perform(
                post("/rest/api/4/admin/teamcity-checkout-layout/sync")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"diffId":"D1","componentIds":[]}"""),
            ).andExpect(status().isAccepted)
    }

    // ---- GET /sync/report.csv: IMPORT_DATA ----

    @Test
    @DisplayName("anonymous GET /sync/report.csv -> 401")
    fun getSyncReportCsvAnonymousReturns401() {
        mvc.perform(get("/rest/api/4/admin/teamcity-checkout-layout/sync/report.csv")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT GET /sync/report.csv -> 403 (component read only, no IMPORT_DATA)")
    fun getSyncReportCsvViewerReturns403() {
        mvc
            .perform(get("/rest/api/4/admin/teamcity-checkout-layout/sync/report.csv").with(viewerJwt()))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("admin JWT GET /sync/report.csv -> 200, text/csv")
    fun getSyncReportCsvAdminReturns200() {
        `when`(syncJobService.current()).thenReturn(
            TeamcityCheckoutLayoutSyncJobState(
                id = "S1",
                state = JobState.COMPLETED,
                startedAt = Instant.now(),
                finishedAt = Instant.now(),
                result = CheckoutLayoutSyncResult("alice", 0, 0, 0, 0, emptyList()),
                errorMessage = null,
            ),
        )

        mvc
            .perform(get("/rest/api/4/admin/teamcity-checkout-layout/sync/report.csv").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv;charset=UTF-8")))
    }

    // ---- GET /diff/report.* : component read only, NOT gated by IMPORT_DATA ----

    @Test
    @DisplayName("anonymous GET /diff/report.json -> 401")
    fun getReportJsonAnonymousReturns401() {
        mvc.perform(get("/rest/api/4/admin/teamcity-checkout-layout/diff/report.json")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("viewer JWT GET /diff/report.json -> 200 (component read is enough)")
    fun getReportJsonViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-checkout-layout/diff/report.json").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
    }

    @Test
    @DisplayName("viewer JWT GET /diff/report.html -> 200, text/html (component read is enough)")
    fun getReportHtmlViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-checkout-layout/diff/report.html").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
    }

    @Test
    @DisplayName("viewer JWT GET /diff/report.csv -> 200, text/csv (component read is enough)")
    fun getReportCsvViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-checkout-layout/diff/report.csv").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv;charset=UTF-8")))
    }

    @Test
    @DisplayName("admin JWT GET /diff/report.json -> 200 (IMPORT_DATA is also enough)")
    fun getReportJsonAdminReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-checkout-layout/diff/report.json").with(adminJwt()))
            .andExpect(status().isOk)
    }

    // ---- deprecated /teamcity-placement paths: same gate and responses, by delegation ----

    @Test
    @DisplayName("deprecated path: viewer JWT POST /diff -> 403")
    fun legacyPostDiffViewerReturns403() {
        mvc.perform(post("/rest/api/4/admin/teamcity-placement/diff").with(viewerJwt())).andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("deprecated path: admin JWT POST /diff -> 202")
    fun legacyPostDiffAdminReturns202() {
        `when`(diffJobService.startAsync("alice")).thenReturn(StartCheckoutLayoutDiffResult(completedDiff(), isNewlyStarted = true))

        mvc
            .perform(post("/rest/api/4/admin/teamcity-placement/diff").with(adminJwt()))
            .andExpect(status().isAccepted)
    }

    @Test
    @DisplayName("deprecated path: viewer JWT GET /diff/report.json -> 200")
    fun legacyGetReportJsonViewerReturns200() {
        `when`(diffJobService.lastCompleted()).thenReturn(completedDiff())

        mvc
            .perform(get("/rest/api/4/admin/teamcity-placement/diff/report.json").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
    }

    @Test
    @DisplayName("deprecated path: admin JWT POST /sync with a replaced Diff -> 409, the message a released Portal matches")
    fun legacyPostSyncStaleDiffReturns409() {
        `when`(syncJobService.startAsync(eq("alice"), eq(emptyList()), eq("OLD"), any()))
            .thenThrow(CheckoutLayoutDiffStaleException())

        mvc
            .perform(
                post("/rest/api/4/admin/teamcity-placement/sync")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"diffId":"OLD","componentIds":[]}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorMessage").value("diff replaced, re-run Diff"))
            .andExpect(jsonPath("$.errorCode").value(CheckoutLayoutDiffStaleException.ERROR_CODE))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(TeamcityCheckoutLayoutControllerV4SecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
