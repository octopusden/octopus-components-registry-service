package org.octopusden.octopus.components.registry.server.teamcity.placement.impl

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
import org.octopusden.octopus.components.registry.server.teamcity.placement.PlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.RejectedExecutionException

@ConditionalOnDatabaseEnabled
@Service
class TeamcityPlacementSyncJobServiceImpl(
    private val syncService: TeamcityPlacementSyncService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcityPlacementSyncJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcityPlacementSyncJobState>(
            jobKind = JobKind.TC_PLACEMENT_SYNC,
            // Owner review finding 7 (simplification): wraps the plain `migrationExecutor`, which
            // does not propagate SecurityContext on its own. DelegatingSecurityContextTaskExecutor
            // captures SecurityContextHolder's context at `.execute()` time (the calling / HTTP
            // request thread, inside claimAndSubmit below) and installs + clears it around the
            // submitted work on whichever thread actually runs it — the same effect the previous
            // manual capture/set/clear achieved, without hand-rolling it here.
            // ComponentManagementServiceImpl resolves `audit_log.changed_by` from
            // SecurityContextHolder at write time, so this is what keeps a Sync write attributed to
            // the triggering user instead of "system".
            executor = DelegatingSecurityContextTaskExecutor(executor),
            gate = lifecycleGate,
            getId = { it.id },
            isRunning = { it.state == JobState.RUNNING },
            markRejected = { current, rejected ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage = "Failed to submit placement sync: ${rejected.message ?: rejected::class.java.simpleName}",
                )
            },
        )

    override fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
        latestDiff: PlacementDiffResult,
    ): StartPlacementSyncResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(
                    buildCandidate = ::buildCandidate,
                    work = { jobId -> runSync(jobId, triggeredBy, componentIds, latestDiff) },
                )
            } catch (rejected: RejectedExecutionException) {
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.TEAMCITY_PLACEMENT_SYNC,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "TeamCity placement sync failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached -> StartPlacementSyncResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started -> StartPlacementSyncResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): TeamcityPlacementSyncJobState? = lifecycle.current()

    private fun buildCandidate(jobId: String): TeamcityPlacementSyncJobState =
        TeamcityPlacementSyncJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    private fun runSync(
        jobId: String,
        triggeredBy: String,
        componentIds: List<UUID>,
        latestDiff: PlacementDiffResult,
    ) {
        serviceEventRecorder.recordStart(
            type = ServiceEventType.TEAMCITY_PLACEMENT_SYNC,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "TeamCity placement sync running",
        )
        try {
            val result = syncService.sync(componentIds.toSet(), latestDiff, jobId, triggeredBy)
            lifecycle.update(jobId) { current -> current.copy(state = JobState.COMPLETED, finishedAt = Instant.now(), result = result) }
            LOG.info(
                "TeamCity placement sync job {} COMPLETED: requested={}, applied={}, skipped={}, failed={}",
                jobId,
                result.requested,
                result.applied,
                result.skipped,
                result.failed,
            )
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_PLACEMENT_SYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary = "TeamCity placement sync completed",
                detail = mapOf(
                    "requested" to result.requested,
                    "applied" to result.applied,
                    "skipped" to result.skipped,
                    "failed" to result.failed,
                ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("TeamCity placement sync job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current ->
                current.copy(state = JobState.FAILED, finishedAt = Instant.now(), errorMessage = e.message ?: e::class.java.simpleName)
            }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_PLACEMENT_SYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "TeamCity placement sync failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcityPlacementSyncJobServiceImpl::class.java)
    }
}
