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
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementSyncService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.RejectedExecutionException

@ConditionalOnDatabaseEnabled
@Service
class TeamcityPlacementSyncJobServiceImpl(
    private val syncService: TeamcityPlacementSyncService,
    private val diffJobService: TeamcityPlacementDiffJobService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcityPlacementSyncJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcityPlacementSyncJobState>(
            jobKind = JobKind.TC_PLACEMENT_SYNC,
            executor = executor,
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
    ): StartPlacementSyncResult {
        // Captured on the calling (HTTP request) thread, BEFORE the executor submit: the plain
        // ThreadPoolTaskExecutor behind `migrationExecutor` does not propagate SecurityContext on
        // its own, and ComponentManagementServiceImpl resolves `audit_log.changed_by` from
        // SecurityContextHolder at write time — without this, every write would attribute to
        // "system" (CurrentUserResolver's no-authentication fallback), not the triggering user.
        val callerSecurityContext = SecurityContextHolder.getContext()
        val outcome =
            try {
                lifecycle.claimAndSubmit(buildCandidate = ::buildCandidate, work = { jobId -> runSync(jobId, triggeredBy, componentIds, callerSecurityContext) })
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
        TeamcityPlacementSyncJobState(id = jobId, state = JobState.RUNNING, startedAt = Instant.now(), finishedAt = null, result = null, errorMessage = null)

    private fun runSync(
        jobId: String,
        triggeredBy: String,
        componentIds: List<UUID>,
        callerSecurityContext: SecurityContext,
    ) {
        // Scoped to this job's whole run (including every write inside syncService.sync), cleared
        // in the outer finally so this executor thread never leaks the caller's identity into a
        // later, unrelated job on the same single-thread pool.
        SecurityContextHolder.setContext(callerSecurityContext)
        try {
            serviceEventRecorder.recordStart(
                type = ServiceEventType.TEAMCITY_PLACEMENT_SYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                summary = "TeamCity placement sync running",
            )
            try {
                val latestDiff = diffJobService.current()?.result
                val result = syncService.sync(componentIds.toSet(), latestDiff, triggeredBy)
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
                    detail = mapOf("requested" to result.requested, "applied" to result.applied, "skipped" to result.skipped, "failed" to result.failed),
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                LOG.error("TeamCity placement sync job {} FAILED", jobId, e)
                lifecycle.update(jobId) { current -> current.copy(state = JobState.FAILED, finishedAt = Instant.now(), errorMessage = e.message ?: e::class.java.simpleName) }
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
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcityPlacementSyncJobServiceImpl::class.java)
    }
}
