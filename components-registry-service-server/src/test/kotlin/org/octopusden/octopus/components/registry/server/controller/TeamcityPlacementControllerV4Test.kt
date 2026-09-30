package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
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
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobState
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
        verify(syncJobService, never()).startAsync(any(), any())
    }

    @Test
    fun `a sync naming the current diff id proceeds`() {
        val componentId = UUID.randomUUID()
        whenever(diffJobService.current()).thenReturn(completedDiff("D2"))
        val syncState = TeamcityPlacementSyncJobState(
            id = "S1",
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )
        whenever(syncJobService.startAsync("alice", listOf(componentId)))
            .thenReturn(StartPlacementSyncResult(syncState, isNewlyStarted = true))

        val response = controller.startSync(TeamcityPlacementSyncRequest(diffId = "D2", componentIds = listOf(componentId)))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        verify(syncJobService).startAsync("alice", listOf(componentId))
    }

    @Test
    fun `no diff has ever run yet, so any requested id is refused`() {
        whenever(diffJobService.current()).thenReturn(null)

        val ex = assertThrows<ResponseStatusException> {
            controller.startSync(TeamcityPlacementSyncRequest(diffId = "D1", componentIds = listOf(UUID.randomUUID())))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(syncJobService, never()).startAsync(any(), any())
    }
}
