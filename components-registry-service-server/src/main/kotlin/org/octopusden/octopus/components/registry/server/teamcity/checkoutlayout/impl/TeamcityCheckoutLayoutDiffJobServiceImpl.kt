package org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.impl

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
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.StartCheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffJobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutDiffService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.RejectedExecutionException

@ConditionalOnDatabaseEnabled
@Service
class TeamcityCheckoutLayoutDiffJobServiceImpl(
    private val diffService: TeamcityCheckoutLayoutDiffService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcityCheckoutLayoutDiffJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcityCheckoutLayoutDiffJobState>(
            jobKind = JobKind.TC_CHECKOUT_LAYOUT_DIFF,
            executor = executor,
            gate = lifecycleGate,
            getId = { it.id },
            isRunning = { it.state == JobState.RUNNING },
            markRejected = { current, rejected ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage = "Failed to submit checkout layout diff: ${rejected.message ?: rejected::class.java.simpleName}",
                )
            },
        )

    @Volatile
    private var lastCompleted: TeamcityCheckoutLayoutDiffJobState? = null

    override fun startAsync(triggeredBy: String): StartCheckoutLayoutDiffResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(buildCandidate = ::buildCandidate, work = { jobId -> runDiff(jobId, triggeredBy) })
            } catch (rejected: RejectedExecutionException) {
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_DIFF,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "TeamCity checkout layout diff failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached -> StartCheckoutLayoutDiffResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started -> StartCheckoutLayoutDiffResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): TeamcityCheckoutLayoutDiffJobState? = lifecycle.current()

    override fun lastCompleted(): TeamcityCheckoutLayoutDiffJobState? = lastCompleted

    private fun buildCandidate(jobId: String): TeamcityCheckoutLayoutDiffJobState =
        TeamcityCheckoutLayoutDiffJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    private fun runDiff(
        jobId: String,
        triggeredBy: String,
    ) {
        serviceEventRecorder.recordStart(
            type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_DIFF,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "TeamCity checkout layout diff running",
        )
        try {
            val result = diffService.runDiff()
            val completed = lifecycle.update(jobId) { current ->
                current.copy(state = JobState.COMPLETED, finishedAt = Instant.now(), result = result.copy(diffId = jobId))
            }
            if (completed?.id == jobId) lastCompleted = completed
            LOG.info("TeamCity checkout layout diff job {} COMPLETED: {} row(s)", jobId, result.rows.size)
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_DIFF,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary = "TeamCity checkout layout diff completed",
                detail = mapOf("rows" to result.rows.size, "byStatus" to result.rows.groupingBy { it.status.name }.eachCount()),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("TeamCity checkout layout diff job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current ->
                current.copy(state = JobState.FAILED, finishedAt = Instant.now(), errorMessage = e.message ?: e::class.java.simpleName)
            }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_DIFF,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "TeamCity checkout layout diff failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcityCheckoutLayoutDiffJobServiceImpl::class.java)
    }
}
