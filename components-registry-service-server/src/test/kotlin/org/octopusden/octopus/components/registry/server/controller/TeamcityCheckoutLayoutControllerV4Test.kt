package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityCheckoutLayoutSyncRequest
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffRowStatus
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutEntryDiff
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutFieldChange
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutRowDiff
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.StartCheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobState
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/**
 * A Sync request names the Diff result it acts on; if that id
 * no longer matches the latest Diff, the whole request must be refused (409) before any component
 * is even considered -- never silently applied against a replaced Diff.
 */
class TeamcityCheckoutLayoutControllerV4Test {
    private val diffJobService = mock<TeamcityCheckoutLayoutDiffJobService>()
    private val syncJobService = mock<TeamcityCheckoutLayoutSyncJobService>()
    private val currentUserResolver = mock<CurrentUserResolver>()
    private val controller = TeamcityCheckoutLayoutControllerV4(diffJobService, syncJobService, currentUserResolver)

    init {
        whenever(currentUserResolver.currentUsername()).thenReturn("alice")
    }

    private fun completedDiff(id: String) =
        TeamcityCheckoutLayoutDiffJobState(
            id = id,
            state = JobState.COMPLETED,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            result = CheckoutLayoutDiffResult(Instant.now(), emptyList(), diffId = id),
            errorMessage = null,
        )

    @Test
    fun `a sync passes the last completed diff result, stamped with its id, down to the job service`() {
        // Re-fetching diffJobService.current() inside the async job
        // (rather than threading through the exact result the id-check validated) reopens the
        // TOCTOU window the diffId check exists to close -- a new Diff completing between the
        // check and the (async) work running would let Sync silently act on an unvalidated
        // result. startAsync must receive the SAME CheckoutLayoutDiffResult instance the check read.
        val componentId = UUID.randomUUID()
        val diffState = completedDiff("D2")
        whenever(diffJobService.lastCompleted()).thenReturn(diffState)
        val syncState = TeamcityCheckoutLayoutSyncJobState(
            id = "S1",
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )
        whenever(syncJobService.startAsync(eq("alice"), eq(listOf(componentId)), eq("D2"), any()))
            .thenReturn(StartCheckoutLayoutSyncResult(syncState, isNewlyStarted = true))

        val response = controller.startSync(TeamcityCheckoutLayoutSyncRequest(diffId = "D2", componentIds = listOf(componentId)))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val latest = argumentCaptor<() -> CheckoutLayoutDiffResult?>()
        verify(syncJobService).startAsync(eq("alice"), eq(listOf(componentId)), eq("D2"), latest.capture())
        assertEquals(diffState.result, latest.firstValue())
    }

    @Test
    fun `the diff report names the Diff run it came from, so a Sync can bind to the report shown`() {
        whenever(diffJobService.lastCompleted()).thenReturn(completedDiff("D7"))

        val body = controller.getReportJson().body!!

        assertEquals("D7", body.diffId)
    }

    @Test
    fun `diff report HTML and CSV have the right content type and escape or format correctly`() {
        val row = CheckoutLayoutRowDiff(
            componentId = UUID.randomUUID(),
            componentKey = "comp-one",
            configurationRowId = UUID.randomUUID(),
            versionRange = "(,0),[0,)",
            rowLabel = "BASE",
            status = CheckoutLayoutDiffRowStatus.CONFLICT,
            entries = listOf(
                CheckoutLayoutEntryDiff(
                    name = "main",
                    vcsPath = "ssh://h/prj/app.git",
                    branch = null,
                    tag = null,
                    hotfixBranch = null,
                    repositoryType = "GIT",
                    currentCheckoutDirectory = null,
                    currentSourcePath = null,
                    derivedCheckoutDirectory = null,
                    derivedSourcePath = null,
                ),
            ),
            currentBuildWorkingDirectory = null,
            derivedBuildWorkingDirectory = null,
            sourceBuildTypeIds = emptyList(),
            notes = listOf("can't be derived"),
        )
        whenever(diffJobService.lastCompleted()).thenReturn(
            completedDiff("D1").copy(result = CheckoutLayoutDiffResult(Instant.now(), listOf(row))),
        )

        // Content-Type for HTML/JSON is set by @GetMapping's `produces` (Spring MVC content
        // negotiation), not observable on a directly-invoked ResponseEntity outside dispatch --
        // pinned instead by the MockMvc round-trip in TeamcityCheckoutLayoutControllerV4SecurityTest.
        val html = controller.getReportHtml()
        assertEquals(HttpStatus.OK, html.statusCode)
        assertTrue(html.body!!.contains("can&#39;t be derived")) // escaped, not the literal apostrophe

        val csv = controller.getReportCsv()
        assertEquals(HttpStatus.OK, csv.statusCode)
        assertEquals("text/csv;charset=UTF-8", csv.headers.getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("attachment; filename=teamcity-checkout-layout-diff.csv", csv.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        assertTrue(csv.body!!.startsWith("componentKey,versionRange,rowLabel,status,"))
    }

    @Test
    fun `sync report CSV has the right content type`() {
        val syncState = TeamcityCheckoutLayoutSyncJobState(
            id = "S1",
            state = JobState.COMPLETED,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            result = CheckoutLayoutSyncResult(
                triggeredBy = "alice",
                requested = 1,
                applied = 1,
                skipped = 0,
                failed = 0,
                components = emptyList(),
                fieldChanges = listOf(CheckoutLayoutFieldChange("comp-one", "BASE", "main", "checkoutDirectory", null, "app")),
            ),
            errorMessage = null,
        )
        whenever(syncJobService.current()).thenReturn(syncState)

        val csv = controller.getSyncReportCsv()

        assertEquals(HttpStatus.OK, csv.statusCode)
        assertEquals("text/csv;charset=UTF-8", csv.headers.getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("attachment; filename=teamcity-checkout-layout-sync.csv", csv.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        assertTrue(csv.body!!.startsWith("componentKey,rowLabel,root,field,before,after"))
    }
}
