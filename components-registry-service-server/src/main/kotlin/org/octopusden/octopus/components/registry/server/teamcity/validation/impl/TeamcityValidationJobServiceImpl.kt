package org.octopusden.octopus.components.registry.server.teamcity.validation.impl

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
import org.octopusden.octopus.components.registry.server.teamcity.validation.StartTeamcityValidationResult
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationJobService
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationJobState
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.RejectedExecutionException

/**
 * Async wrapper around [TeamcityValidationService.validate] — mirrors `TeamcitySyncJobServiceImpl`
 * with `JobKind.TC_VALIDATION`: gate-serialized and single-flight (one validation at a time).
 */
@ConditionalOnDatabaseEnabled
@Service
class TeamcityValidationJobServiceImpl(
    private val teamcityValidationService: TeamcityValidationService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcityValidationJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcityValidationJobState>(
            jobKind = JobKind.TC_VALIDATION,
            executor = executor,
            gate = lifecycleGate,
            getId = { it.id },
            isRunning = { it.state == JobState.RUNNING },
            markRejected = { current, rejected ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage =
                        "Failed to submit teamcity validation: ${rejected.message ?: rejected::class.java.simpleName}",
                )
            },
        )

    override fun startAsync(triggeredBy: String): StartTeamcityValidationResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(
                    buildCandidate = ::buildCandidate,
                    work = { jobId -> runValidation(jobId, triggeredBy) },
                )
            } catch (rejected: RejectedExecutionException) {
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.TEAMCITY_VALIDATION,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "TeamCity validation failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached ->
                StartTeamcityValidationResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started ->
                StartTeamcityValidationResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): TeamcityValidationJobState? = lifecycle.current()

    private fun buildCandidate(jobId: String): TeamcityValidationJobState =
        TeamcityValidationJobState(
            id = jobId,
            state = JobState.RUNNING,
            startedAt = Instant.now(),
            finishedAt = null,
            result = null,
            errorMessage = null,
        )

    private fun runValidation(
        jobId: String,
        triggeredBy: String,
    ) {
        serviceEventRecorder.recordStart(
            type = ServiceEventType.TEAMCITY_VALIDATION,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "TeamCity validation running",
        )
        try {
            val result = teamcityValidationService.validate()
            lifecycle.update(jobId) { current ->
                current.copy(
                    state = JobState.COMPLETED,
                    finishedAt = Instant.now(),
                    result = result,
                )
            }
            LOG.info(
                "TC validation job {} COMPLETED: scanned={}, succeeded={}, failed={}, " +
                    "projectsWithIssues={}, removed={}, errors={}",
                jobId,
                result.scanned,
                result.succeeded,
                result.failed,
                result.projectsWithIssues,
                result.removed,
                result.errors.size,
            )
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_VALIDATION,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary =
                    if (result.failed > 0) {
                        "TeamCity validation completed with per-project failures"
                    } else {
                        "TeamCity validation completed"
                    },
                detail =
                    mapOf(
                        "scanned" to result.scanned,
                        "succeeded" to result.succeeded,
                        "failed" to result.failed,
                        "projectsWithIssues" to result.projectsWithIssues,
                        "removed" to result.removed,
                        "errors" to result.errors,
                    ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("TC validation job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage = e.message ?: e::class.java.simpleName,
                )
            }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_VALIDATION,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "TeamCity validation failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcityValidationJobServiceImpl::class.java)
    }
}
