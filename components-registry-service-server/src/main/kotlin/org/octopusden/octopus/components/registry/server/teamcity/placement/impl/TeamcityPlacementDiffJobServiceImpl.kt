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
import org.octopusden.octopus.components.registry.server.teamcity.placement.StartPlacementDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.placement.TeamcityPlacementDiffService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.RejectedExecutionException

@ConditionalOnDatabaseEnabled
@Service
class TeamcityPlacementDiffJobServiceImpl(
    private val diffService: TeamcityPlacementDiffService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcityPlacementDiffJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcityPlacementDiffJobState>(
            jobKind = JobKind.TC_PLACEMENT_DIFF,
            executor = executor,
            gate = lifecycleGate,
            getId = { it.id },
            isRunning = { it.state == JobState.RUNNING },
            markRejected = { current, rejected ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage = "Failed to submit placement diff: ${rejected.message ?: rejected::class.java.simpleName}",
                )
            },
        )

    override fun startAsync(triggeredBy: String): StartPlacementDiffResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(buildCandidate = ::buildCandidate, work = { jobId -> runDiff(jobId, triggeredBy) })
            } catch (rejected: RejectedExecutionException) {
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.TEAMCITY_PLACEMENT_DIFF,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "TeamCity placement diff failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached -> StartPlacementDiffResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started -> StartPlacementDiffResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): TeamcityPlacementDiffJobState? = lifecycle.current()

    private fun buildCandidate(jobId: String): TeamcityPlacementDiffJobState =
        TeamcityPlacementDiffJobState(id = jobId, state = JobState.RUNNING, startedAt = Instant.now(), finishedAt = null, result = null, errorMessage = null)

    private fun runDiff(
        jobId: String,
        triggeredBy: String,
    ) {
        serviceEventRecorder.recordStart(
            type = ServiceEventType.TEAMCITY_PLACEMENT_DIFF,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "TeamCity placement diff running",
        )
        try {
            val result = diffService.runDiff()
            lifecycle.update(jobId) { current -> current.copy(state = JobState.COMPLETED, finishedAt = Instant.now(), result = result) }
            LOG.info("TeamCity placement diff job {} COMPLETED: {} row(s)", jobId, result.rows.size)
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_PLACEMENT_DIFF,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary = "TeamCity placement diff completed",
                detail = mapOf("rows" to result.rows.size, "byStatus" to result.rows.groupingBy { it.status.name }.eachCount()),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("TeamCity placement diff job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current -> current.copy(state = JobState.FAILED, finishedAt = Instant.now(), errorMessage = e.message ?: e::class.java.simpleName) }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_PLACEMENT_DIFF,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "TeamCity placement diff failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcityPlacementDiffJobServiceImpl::class.java)
    }
}
