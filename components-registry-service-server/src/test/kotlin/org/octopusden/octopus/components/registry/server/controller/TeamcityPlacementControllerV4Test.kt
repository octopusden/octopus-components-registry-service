package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.components.registry.server.dto.v4.TeamcityPlacementSyncRequest
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffRowStatus
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementEntryDiff
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementFieldChange
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementRowDiff
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobState
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Owner review of PR #510 (finding 1): a Sync request names the Diff result it acts on; if that id
 * no longer matches the latest Diff, the whole request must be refused (409) before any component
 * is even considered -- never silently applied against a replaced Diff.
 */
class TeamcityPlacementControllerV4Test {
    private val diffJobService = mock<TeamcityPlacementDiffJobService>()
    private val syncJobService = mock<TeamcityPlacementSyncJobService>()
    private val currentUserResolver = mock<CurrentUserResolver>()
    private val controller = TeamcityPlacementControllerV4(diffJobService, syncJobService, currentUserResolver)

    init {
        whenever(currentUserResolver.currentUsername()).thenReturn("alice")
    }

    private fun completedDiff(id: String) =
        TeamcityPlacementDiffJobState(
            id = id,
            state = JobState.COMPLETED,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            result = PlacementDiffResult(Instant.now(), emptyList()),
            errorMessage = null,
        )

    @Test
    fun `a sync naming a replaced diff id is refused with 409 before anything is considered`() {
        // Diff ran twice (D1, then D2); the request still names D1.
        whenever(diffJobService.current()).thenReturn(completedDiff("D2"))

        val ex = assertThrows<ResponseStatusException> {
            controller.startSync(TeamcityPlacementSyncRequest(diffId = "D1", componentIds = listOf(UUID.randomUUID())))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(syncJobService, never()).startAsync(any(), any(), any())
    }

    @Test
    fun `a sync naming the current diff id proceeds, passing the SAME diff result down (owner review finding 1 hardening, RED)`() {
        // Codex second-pass finding: re-fetching diffJobService.current() inside the async job
        // (rather than threading through the exact result the id-check validated) reopens the
        // TOCTOU window the diffId check exists to close -- a new Diff completing between the
        // check and the (async) work running would let Sync silently act on an unvalidated
        // result. startAsync must receive the SAME PlacementDiffResult instance the check read.
        val componentId = UUID.randomUUID()
        val diffState = completedDiff("D2")
        whenever(diffJobService.current()).thenReturn(diffState)
        val syncState = TeamcityPlacementSyncJobState(
            id = "S1",
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )
        whenever(syncJobService.startAsync("alice", listOf(componentId), diffState.result!!))
            .thenReturn(StartPlacementSyncResult(syncState, isNewlyStarted = true))

        val response = controller.startSync(TeamcityPlacementSyncRequest(diffId = "D2", componentIds = listOf(componentId)))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        verify(syncJobService).startAsync("alice", listOf(componentId), diffState.result!!)
    }

    @Test
    fun `no diff has ever run yet, so any requested id is refused`() {
        whenever(diffJobService.current()).thenReturn(null)

        val ex = assertThrows<ResponseStatusException> {
            controller.startSync(TeamcityPlacementSyncRequest(diffId = "D1", componentIds = listOf(UUID.randomUUID())))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(syncJobService, never()).startAsync(any(), any(), any())
    }

    @Test
    fun `a diff id matching a RUNNING (not yet completed) diff is refused -- no result to sync against`() {
        // The id column alone isn't enough: a fresh Diff run immediately publishes a new id with
        // a null result while it's still RUNNING. Matching that id must not be treated as "the
        // caller's diffId is current" -- there is no completed result to bind the write to yet.
        val running = TeamcityPlacementDiffJobState(
            id = "D3",
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )
        whenever(diffJobService.current()).thenReturn(running)

        val ex = assertThrows<ResponseStatusException> {
            controller.startSync(TeamcityPlacementSyncRequest(diffId = "D3", componentIds = listOf(UUID.randomUUID())))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(syncJobService, never()).startAsync(any(), any(), any())
    }

    @Test
    fun `a new Diff replaces the latest result -- the old diffId is refused while reports reflect the new one (finding 4)`() {
        val d1 = completedDiff("D1")
        whenever(diffJobService.current()).thenReturn(d1)
        assertEquals("D1", controller.getReportJson().body!!.diffId)

        // A second Diff completes, replacing the in-memory result.
        val d2 = completedDiff("D2")
        whenever(diffJobService.current()).thenReturn(d2)

        val ex = assertThrows<ResponseStatusException> {
            controller.startSync(TeamcityPlacementSyncRequest(diffId = "D1", componentIds = listOf(UUID.randomUUID())))
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(syncJobService, never()).startAsync(any(), any(), any())
        // The report endpoints already reflect D2, not the replaced D1.
        assertEquals("D2", controller.getReportJson().body!!.diffId)
    }

    @Test
    fun `the diff report names the Diff run it came from, so a Sync can bind to the report shown (review P1-3, RED)`() {
        whenever(diffJobService.current()).thenReturn(completedDiff("D7"))

        val body = controller.getReportJson().body!!

        assertEquals("D7", body.diffId)
    }

    @Test
    fun `diff report HTML and CSV have the right content type and escape or format correctly (spec-conformance finding 4 coverage)`() {
        val row = PlacementRowDiff(
            componentId = UUID.randomUUID(),
            componentKey = "comp-one",
            configurationRowId = UUID.randomUUID(),
            versionRange = "(,0),[0,)",
            rowLabel = "BASE",
            status = PlacementDiffRowStatus.CONFLICT,
            entries = listOf(
                PlacementEntryDiff(
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
        whenever(diffJobService.current()).thenReturn(
            completedDiff("D1").copy(result = PlacementDiffResult(Instant.now(), listOf(row))),
        )

        // Content-Type for HTML/JSON is set by @GetMapping's `produces` (Spring MVC content
        // negotiation), not observable on a directly-invoked ResponseEntity outside dispatch --
        // pinned instead by the MockMvc round-trip in TeamcityPlacementControllerV4SecurityTest.
        val html = controller.getReportHtml()
        assertEquals(HttpStatus.OK, html.statusCode)
        assertTrue(html.body!!.contains("can&#39;t be derived")) // escaped, not the literal apostrophe

        val csv = controller.getReportCsv()
        assertEquals(HttpStatus.OK, csv.statusCode)
        assertEquals("text/csv;charset=UTF-8", csv.headers.getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("attachment; filename=teamcity-placement-diff.csv", csv.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        assertTrue(csv.body!!.startsWith("componentKey,versionRange,rowLabel,status,"))
    }

    @Test
    fun `sync report CSV has the right content type (spec-conformance finding 4 coverage)`() {
        val syncState = TeamcityPlacementSyncJobState(
            id = "S1",
            state = JobState.COMPLETED,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            result = PlacementSyncResult(
                triggeredBy = "alice",
                requested = 1,
                applied = 1,
                skipped = 0,
                failed = 0,
                components = emptyList(),
                fieldChanges = listOf(PlacementFieldChange("comp-one", "BASE", "main", "checkoutDirectory", null, "app")),
            ),
            errorMessage = null,
        )
        whenever(syncJobService.current()).thenReturn(syncState)

        val csv = controller.getSyncReportCsv()

        assertEquals(HttpStatus.OK, csv.statusCode)
        assertEquals("text/csv;charset=UTF-8", csv.headers.getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("attachment; filename=teamcity-placement-sync.csv", csv.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        assertTrue(csv.body!!.startsWith("componentKey,rowLabel,root,field,before,after"))
    }
}
