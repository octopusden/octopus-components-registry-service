package org.octopusden.octopus.components.registry.server.teamcity.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.service.AsyncJobLifecycle
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind
import org.octopusden.octopus.components.registry.server.service.NoOpServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.octopusden.octopus.components.registry.server.teamcity.StartTeamcitySyncResult
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncJobState
import org.octopusden.octopus.components.registry.server.teamcity.TeamcitySyncService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * In-memory async wrapper around [TeamcitySyncService.resync]. Mirrors
 * `MigrationJobServiceImpl` for the components flow — same gate integration via
 * [AsyncJobLifecycle], same CAS-then-publish ordering, same finally-release.
 *
 * No DB-backed claim: TC sync is not resumable, so a pod restart starts the
 * next run from scratch. There is also no `forceReset` / `clearInMemory`
 * surface — those exist on history because of its DB-backed
 * `git_history_import_state` row; TC has nothing equivalent to clear.
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcitySyncJobServiceImpl(
    private val teamcitySyncService: TeamcitySyncService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcitySyncJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcitySyncJobState>(
            jobKind = JobKind.TC_RESYNC,
            executor = executor,
            gate = lifecycleGate,
            getId = { it.id },
            isRunning = { it.state == JobState.RUNNING },
            markRejected = { current, rejected ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage =
                        "Failed to submit teamcity resync: ${rejected.message ?: rejected::class.java.simpleName}",
                )
            },
        )

    override fun startAsync(triggeredBy: String): StartTeamcitySyncResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(
                    buildCandidate = ::buildCandidate,
                    work = { jobId -> runResync(jobId, triggeredBy) },
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") rejected: Throwable,
            ) {
                // Executor rejected the submission (typically pod shutdown): the work
                // runnable never ran, so recordStart never fired. Record a standalone
                // terminal FAILED row so the failure is not lost (SYS-060), then let the
                // rejection propagate as before.
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.TEAMCITY_RESYNC,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "TeamCity resync failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached ->
                StartTeamcitySyncResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started ->
                StartTeamcitySyncResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): TeamcitySyncJobState? = lifecycle.current()

    private fun buildCandidate(jobId: String): TeamcitySyncJobState =
        TeamcitySyncJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    private fun runResync(
        jobId: String,
        triggeredBy: String,
    ) {
        // recordStart runs here (executor thread) rather than at claim time so the
        // RUNNING journal row is written before the finish transition in BOTH the
        // async executor and the SyncTaskExecutor (tests) — ordering that a
        // claim-time write cannot guarantee. triggeredBy is captured on the caller
        // thread and passed in, so no SecurityContext propagation is needed here.
        serviceEventRecorder.recordStart(
            type = ServiceEventType.TEAMCITY_RESYNC,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "TeamCity resync running",
        )
        try {
            val result = teamcitySyncService.resync()
            lifecycle.update(jobId) { current ->
                current.copy(
                    state = JobState.COMPLETED,
                    finishedAt = Instant.now(),
                    result = result,
                )
            }
            LOG.info(
                "TC resync job {} COMPLETED: scanned={}, updated={}, unchanged={}, " +
                    "skippedNoMatch={}, skippedAmbiguous={}, ambiguousAutoResolved={}, errors={}",
                jobId,
                result.scanned,
                result.updated,
                result.unchanged,
                result.skippedNoMatch,
                result.skippedAmbiguous,
                result.ambiguousAutoResolved,
                result.errors.size,
            )
            // A run that matched everything but hit per-component errors still COMPLETED
            // the pass; flag it so the Events feed distinguishes clean from partial runs.
            val completedWithErrors = result.errors.isNotEmpty()
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_RESYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary = if (completedWithErrors) "TeamCity resync completed with errors" else "TeamCity resync completed",
                detail =
                    mapOf(
                        "scanned" to result.scanned,
                        "updated" to result.updated,
                        "unchanged" to result.unchanged,
                        "skippedNoMatch" to result.skippedNoMatch,
                        "skippedAmbiguous" to result.skippedAmbiguous,
                        "ambiguousAutoResolved" to result.ambiguousAutoResolved,
                        "errors" to result.errors,
                    ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("TC resync job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage = e.message ?: e::class.java.simpleName,
                )
            }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_RESYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "TeamCity resync failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcitySyncJobServiceImpl::class.java)
    }
}
