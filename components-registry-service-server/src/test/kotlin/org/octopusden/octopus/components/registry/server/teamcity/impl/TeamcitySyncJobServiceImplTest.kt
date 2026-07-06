package org.octopusden.octopus.components.registry.server.teamcity.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.octopusden.octopus.components.registry.server.support.RecordingServiceEventRecorder
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncResult
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncService
import org.springframework.core.task.SyncTaskExecutor
import kotlin.test.assertFailsWith

/**
 * Pins the contract that `TeamcitySyncJobServiceImpl` promises to the
 * controller and the weekly scheduler:
 *  - Only one TC resync at a time. Same-kind 409 attach returns the existing
 *    state with `isNewlyStarted=false`; cross-kind throws
 *    [MigrationConflictException].
 *  - Terminal transitions (COMPLETED / FAILED) populate finishedAt and
 *    surface the underlying [TeamcitySyncResult] (or `errorMessage`) for
 *    the SPA to render.
 *  - Executor rejection rolls back the slot to FAILED with the standard
 *    "Failed to submit teamcity resync: ..." prefix and releases the gate.
 *
 * [SyncTaskExecutor] runs the work inline so terminal state is observable
 * the moment `startAsync` returns. [TeamcitySyncService] is mocked via
 * Mockito here — `resync()` takes no arguments, so the Kotlin non-nullable
 * arg trap that the migration-job tests work around with handwritten stubs
 * does not apply.
 */
class TeamcitySyncJobServiceImplTest {
    private val emptyResult =
        TeamcitySyncResult(
            scanned = 0,
            updated = 0,
            unchanged = 0,
            skippedNoMatch = 0,
            skippedAmbiguous = 0,
            ambiguousAutoResolved = 0,
            errors = emptyList(),
        )

    @Test
    @DisplayName("current() is null before any job has been started")
    fun currentIsNullBeforeFirstStart() {
        val service = TeamcitySyncJobServiceImpl(mock(TeamcitySyncService::class.java), SyncTaskExecutor(), MigrationLifecycleGate())
        assertNull(service.current())
    }

    @Test
    @DisplayName("first startAsync claims gate, runs, completes")
    fun firstStartCompletes() {
        val populated =
            TeamcitySyncResult(
                scanned = 12,
                updated = 3,
                unchanged = 7,
                skippedNoMatch = 1,
                skippedAmbiguous = 1,
                ambiguousAutoResolved = 0,
                errors = listOf("oops on alpha"),
            )
        val syncService = mock(TeamcitySyncService::class.java)
        `when`(syncService.resync()).thenReturn(populated)
        val gate = MigrationLifecycleGate()
        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), gate)

        val result = service.startAsync()

        assertTrue(result.isNewlyStarted, "fresh job should be newly-started")
        // SyncTaskExecutor: by the time startAsync returns, the work has finished.
        assertEquals(JobState.COMPLETED, result.state.state)
        assertEquals(populated, result.state.result)
        verify(syncService, times(1)).resync()
        assertNull(gate.current(), "gate must be released on COMPLETED")
    }

    @Test
    @DisplayName("second startAsync while RUNNING returns isNewlyStarted=false with same job state")
    fun secondStartAttaches() {
        val deferred = DeferredExecutor()
        val syncService = mock(TeamcitySyncService::class.java)
        `when`(syncService.resync()).thenReturn(emptyResult)
        val service = TeamcitySyncJobServiceImpl(syncService, deferred, MigrationLifecycleGate())

        val first = service.startAsync()
        // DeferredExecutor: the work hasn't actually run yet, so the slot is RUNNING.
        assertEquals(JobState.RUNNING, first.state.state)
        assertTrue(first.isNewlyStarted)

        val second = service.startAsync()

        assertFalse(second.isNewlyStarted, "second start while RUNNING must NOT be flagged newly-started")
        assertEquals(first.state.id, second.state.id, "second start must echo back the SAME job id")
        assertEquals(1, deferred.taskCount, "executor must have been handed exactly one task")
    }

    @Test
    @DisplayName("startAsync throws MigrationConflictException when COMPONENTS gate is held")
    fun crossKindComponentsConflict() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(MigrationLifecycleGate.JobKind.COMPONENTS, "components-1")
        val syncService = mock(TeamcitySyncService::class.java)

        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), gate)

        val ex = assertFailsWith<MigrationConflictException> { service.startAsync() }
        assertEquals(MigrationLifecycleGate.JobKind.COMPONENTS, ex.active.kind)
        assertEquals("components-1", ex.active.jobId)
        verify(syncService, never()).resync()
    }

    @Test
    @DisplayName("startAsync throws MigrationConflictException when HISTORY gate is held")
    fun crossKindHistoryConflict() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(MigrationLifecycleGate.JobKind.HISTORY, "history-1")
        val syncService = mock(TeamcitySyncService::class.java)

        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), gate)

        val ex = assertFailsWith<MigrationConflictException> { service.startAsync() }
        assertEquals(MigrationLifecycleGate.JobKind.HISTORY, ex.active.kind)
        assertEquals("history-1", ex.active.jobId)
        verify(syncService, never()).resync()
    }

    @Test
    @DisplayName("executor rejection transitions slot to FAILED and releases gate")
    fun executorRejectionRollsBack() {
        val syncService = mock(TeamcitySyncService::class.java)
        val gate = MigrationLifecycleGate()
        val service = TeamcitySyncJobServiceImpl(syncService, RejectingExecutor("pool shutting down"), gate)

        val thrown = assertFailsWith<java.util.concurrent.RejectedExecutionException> { service.startAsync() }
        assertEquals("pool shutting down", thrown.message)

        val state = service.current()!!
        assertEquals(JobState.FAILED, state.state)
        assertNotNull(state.finishedAt)
        assertEquals("Failed to submit teamcity resync: pool shutting down", state.errorMessage)
        verify(syncService, never()).resync()
        assertNull(gate.current(), "rejection must release the gate to avoid wedging follow-up runs")
    }

    @Test
    @DisplayName("runtime exception inside resync transitions slot to FAILED, gate released")
    fun resyncFailureTransitionsToFailed() {
        val syncService = mock(TeamcitySyncService::class.java)
        `when`(syncService.resync()).thenThrow(IllegalStateException("TC unavailable"))
        val gate = MigrationLifecycleGate()
        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), gate)

        service.startAsync()
        val state = service.current()!!

        assertEquals(JobState.FAILED, state.state)
        assertNotNull(state.finishedAt)
        assertEquals("TC unavailable", state.errorMessage)
        assertNull(state.result)
        assertNull(gate.current(), "FAILED must release the gate to avoid wedging follow-up runs")
    }

    @Test
    @DisplayName("after job COMPLETES the next startAsync starts a fresh job with a different id")
    fun nextStartAfterCompleted() {
        val syncService = mock(TeamcitySyncService::class.java)
        `when`(syncService.resync()).thenReturn(emptyResult)
        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), MigrationLifecycleGate())

        val first = service.startAsync()
        assertEquals(JobState.COMPLETED, first.state.state)

        val second = service.startAsync()
        assertTrue(second.isNewlyStarted)
        assertTrue(first.state.id != second.state.id, "completed job should not block a new run")
        verify(syncService, times(2)).resync()
    }

    @Test
    @DisplayName("SYS-060 completed run records COMPLETED with result counters + triggeredBy")
    fun sys060RecordsCompleted() {
        val populated =
            TeamcitySyncResult(
                scanned = 5, updated = 2, unchanged = 3,
                skippedNoMatch = 0, skippedAmbiguous = 0, ambiguousAutoResolved = 0, errors = emptyList(),
            )
        val syncService = mock(TeamcitySyncService::class.java)
        `when`(syncService.resync()).thenReturn(populated)
        val recorder = RecordingServiceEventRecorder()
        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), MigrationLifecycleGate(), recorder)

        service.startAsync("alice")

        // recordStart precedes recordFinish; one row per run.
        assertEquals(listOf("start", "finish:COMPLETED"), recorder.order)
        val start = recorder.starts.single()
        assertEquals(ServiceEventType.TEAMCITY_RESYNC, start.type)
        assertEquals(ServiceEventSource.CRS, start.source)
        assertEquals("alice", start.triggeredBy)
        val finish = recorder.finishes.single()
        assertEquals(ServiceEventStatus.COMPLETED, finish.status)
        assertEquals(start.correlationId, finish.correlationId)
        assertEquals(2, finish.detail?.get("updated"))
    }

    @Test
    @DisplayName("SYS-060 failed run records FAILED with the error message")
    fun sys060RecordsFailed() {
        val syncService = mock(TeamcitySyncService::class.java)
        `when`(syncService.resync()).thenThrow(IllegalStateException("TC unavailable"))
        val recorder = RecordingServiceEventRecorder()
        val service = TeamcitySyncJobServiceImpl(syncService, SyncTaskExecutor(), MigrationLifecycleGate(), recorder)

        service.startAsync("alice")

        assertEquals(listOf("start", "finish:FAILED"), recorder.order)
        val finish = recorder.finishes.single()
        assertEquals(ServiceEventStatus.FAILED, finish.status)
        assertEquals("TC unavailable", finish.detail?.get("errorMessage"))
    }

    @Test
    @DisplayName("SYS-060 rejected submission records a standalone FAILED (no running row)")
    fun sys060RecordsRejected() {
        val syncService = mock(TeamcitySyncService::class.java)
        val recorder = RecordingServiceEventRecorder()
        val service = TeamcitySyncJobServiceImpl(syncService, RejectingExecutor("pool shutting down"), MigrationLifecycleGate(), recorder)

        assertFailsWith<java.util.concurrent.RejectedExecutionException> { service.startAsync("alice") }

        // The runnable never ran, so no recordStart; a terminal FAILED is recorded directly.
        assertTrue(recorder.starts.isEmpty())
        val instant = recorder.instants.single()
        assertEquals(ServiceEventType.TEAMCITY_RESYNC, instant.type)
        assertEquals(ServiceEventStatus.FAILED, instant.status)
    }

    @Test
    @DisplayName("SYS-060 cross-kind conflict records NOTHING (a conflict is not a failure)")
    fun sys060CrossKindConflictRecordsNothing() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(MigrationLifecycleGate.JobKind.COMPONENTS, "components-1")
        val recorder = RecordingServiceEventRecorder()
        val service = TeamcitySyncJobServiceImpl(mock(TeamcitySyncService::class.java), SyncTaskExecutor(), gate, recorder)

        assertFailsWith<MigrationConflictException> { service.startAsync("alice") }

        // A cross-kind conflict means the job never started — it must NOT be journaled as FAILED.
        assertTrue(recorder.starts.isEmpty())
        assertTrue(recorder.finishes.isEmpty())
        assertTrue(recorder.instants.isEmpty())
    }

    // -- Test doubles -------------------------------------------------------

    /** Capture-but-don't-run executor — observe RUNNING state without the work finishing. */
    private class DeferredExecutor : org.springframework.core.task.TaskExecutor {
        var taskCount: Int = 0
            private set

        override fun execute(task: Runnable) {
            taskCount++
            // Intentionally NOT running the task.
        }
    }

    /** Always throws [java.util.concurrent.RejectedExecutionException] — simulates pod shutdown / queue full. */
    private class RejectingExecutor(
        private val reason: String,
    ) : org.springframework.core.task.TaskExecutor {
        override fun execute(task: Runnable): Unit = throw java.util.concurrent.RejectedExecutionException(reason)
    }
}
