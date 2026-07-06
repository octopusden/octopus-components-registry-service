package org.octopusden.octopus.components.registry.server.teamcity

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import java.time.Instant

/**
 * Pins the contract that the weekly cron promises to operations:
 *  - On a freshly-claimed slot it just kicks the job off and returns; the
 *    actual sync runs on `migrationExecutor` and its outcome lands in the
 *    job state, not in the cron call's stack.
 *  - Same-kind attach (TC sync already RUNNING from an admin click): logged
 *    + skipped, no exception escapes the @Scheduled tick.
 *  - Cross-kind ([MigrationConflictException]): logged + skipped; same
 *    no-throw guarantee.
 *  - Any other RuntimeException from `startAsync` (typically
 *    RejectedExecutionException on shutdown) is caught — Spring's
 *    @Scheduled task scheduler considers `startAsync` failures fatal in
 *    some configurations and would suppress further weekly fires.
 */
class TeamcitySyncSchedulerTest {
    private fun startedState(jobId: String) =
        TeamcitySyncJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    @Test
    @DisplayName("weeklyResync calls startAsync and returns normally on isNewlyStarted=true")
    fun startsFreshJob() {
        val jobService = mock(TeamcitySyncJobService::class.java)
        `when`(jobService.startAsync("scheduler")).thenReturn(StartTeamcitySyncResult(startedState("job-1"), isNewlyStarted = true))

        TeamcitySyncScheduler(jobService).weeklyResync()

        verify(jobService, times(1)).startAsync("scheduler")
    }

    @Test
    @DisplayName("weeklyResync swallows isNewlyStarted=false (same-kind attach) without throwing")
    fun swallowsSameKindAttach() {
        val jobService = mock(TeamcitySyncJobService::class.java)
        `when`(jobService.startAsync("scheduler")).thenReturn(StartTeamcitySyncResult(startedState("job-2"), isNewlyStarted = false))

        // Must NOT throw — catching the same-kind attach in @Scheduled is the entire point.
        TeamcitySyncScheduler(jobService).weeklyResync()
    }

    @Test
    @DisplayName("weeklyResync swallows MigrationConflictException (cross-kind) without throwing")
    fun swallowsCrossKindConflict() {
        val jobService = mock(TeamcitySyncJobService::class.java)
        `when`(jobService.startAsync("scheduler"))
            .thenThrow(
                MigrationConflictException(
                    MigrationLifecycleGate.ActiveJob(MigrationLifecycleGate.JobKind.HISTORY, "history-1"),
                ),
            )

        // Must NOT propagate — Spring's @Scheduled would treat propagation as a fatal task failure.
        TeamcitySyncScheduler(jobService).weeklyResync()
    }

    @Test
    @DisplayName("weeklyResync swallows generic RuntimeException without throwing (back-compat)")
    fun swallowsGenericRuntimeException() {
        val jobService = mock(TeamcitySyncJobService::class.java)
        `when`(jobService.startAsync("scheduler")).thenThrow(RuntimeException("transient pool issue"))

        // Pre-refactor scheduler had a catch-all — preserve that so a transient
        // RejectedExecutionException doesn't suppress next week's fire.
        TeamcitySyncScheduler(jobService).weeklyResync()
    }
}
