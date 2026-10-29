package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.teamcity.sync.TeamcitySyncCompletedEvent
import java.time.Instant

/**
 * SYS-088: `TeamcityValidationSyncListener` runs a validation job after every successful TeamCity
 * sync, async and best-effort. Covers the three acceptance criteria that had no dedicated test
 * before this PR: exactly-once trigger, cache invalidation ordered before the trigger, and
 * exception containment (a `startAsync` failure must never propagate back to the sync's own
 * completion path).
 */
class TeamcityValidationSyncListenerTest {
    private val jobService = mock(TeamcityValidationJobService::class.java)
    private val fetcher = mock(EnrichedTcProjectFetcher::class.java)
    private val listener = TeamcityValidationSyncListener(jobService, fetcher)

    private fun runningState(id: String) =
        TeamcityValidationJobState(
            id = id,
            state = JobState.RUNNING,
            startedAt = Instant.parse("2026-07-22T00:00:00Z"),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    @Test
    @DisplayName("SYS-088 a sync-completed event invalidates the fetch cache and triggers startAsync exactly once, in that order")
    fun SYS_088_syncCompleted_invalidatesCacheThenTriggersStartAsyncOnce() {
        `when`(jobService.startAsync("post-sync"))
            .thenReturn(StartTeamcityValidationResult(runningState("job-1"), isNewlyStarted = true))

        listener.onSyncCompleted(TeamcitySyncCompletedEvent(jobId = "sync-1", triggeredBy = "cron"))

        val order: InOrder = inOrder(fetcher, jobService)
        order.verify(fetcher, times(1)).invalidateAll()
        order.verify(jobService, times(1)).startAsync("post-sync")
        verify(jobService, times(1)).startAsync(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    @DisplayName("SYS-088 an exception from startAsync is caught and does not propagate")
    fun SYS_088_startAsyncException_isCaughtAndDoesNotPropagate() {
        `when`(jobService.startAsync("post-sync")).thenThrow(IllegalStateException("boom"))

        assertDoesNotThrow {
            listener.onSyncCompleted(TeamcitySyncCompletedEvent(jobId = "sync-2", triggeredBy = "cron"))
        }

        verify(fetcher, times(1)).invalidateAll()
        verify(jobService, times(1)).startAsync("post-sync")
    }

    @Test
    @DisplayName("SYS-088 an exception from invalidateAll is also caught and startAsync is never reached")
    fun SYS_088_invalidateAllException_isCaughtAndStartAsyncNeverCalled() {
        `when`(fetcher.invalidateAll()).thenThrow(IllegalStateException("cache boom"))

        assertDoesNotThrow {
            listener.onSyncCompleted(TeamcitySyncCompletedEvent(jobId = "sync-3", triggeredBy = "cron"))
        }

        verify(jobService, never()).startAsync(org.mockito.ArgumentMatchers.anyString())
    }
}
