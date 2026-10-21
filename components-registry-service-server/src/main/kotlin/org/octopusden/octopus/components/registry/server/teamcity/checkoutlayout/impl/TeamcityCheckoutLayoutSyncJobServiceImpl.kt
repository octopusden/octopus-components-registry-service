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
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.CheckoutLayoutDiffStaleException
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.StartCheckoutLayoutSyncResult
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobService
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncJobState
import org.octopusden.octopus.components.registry.server.teamcity.checkoutlayout.TeamcityCheckoutLayoutSyncService
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
class TeamcityCheckoutLayoutSyncJobServiceImpl(
    private val syncService: TeamcityCheckoutLayoutSyncService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : TeamcityCheckoutLayoutSyncJobService {
    private val lifecycle =
        AsyncJobLifecycle<TeamcityCheckoutLayoutSyncJobState>(
            jobKind = JobKind.TC_CHECKOUT_LAYOUT_SYNC,
            // Wraps the plain `migrationExecutor`, which
            // does not propagate SecurityContext on its own. DelegatingSecurityContextTaskExecutor
            // captures SecurityContextHolder's context at `.execute()` time (the calling / HTTP
            // request thread, inside claimAndSubmit below) and installs + clears it around the
            // submitted work on whichever thread actually runs it.
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
                    errorMessage = "Failed to submit checkout layout sync: ${rejected.message ?: rejected::class.java.simpleName}",
                )
            },
        )

    override fun startAsync(
        triggeredBy: String,
        componentIds: List<UUID>,
        requestedDiffId: String,
        latestDiff: () -> CheckoutLayoutDiffResult?,
    ): StartCheckoutLayoutSyncResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(
                    buildCandidate = ::buildCandidate,
                    work = { jobId, diff -> runSync(jobId, triggeredBy, componentIds, diff) },
                    // Read once, under the gate, and handed as-is to the work.
                    beforePublish = {
                        latestDiff()?.takeIf { it.diffId == requestedDiffId } ?: throw CheckoutLayoutDiffStaleException()
                    },
                )
            } catch (rejected: RejectedExecutionException) {
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_SYNC,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "TeamCity checkout layout sync failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached -> StartCheckoutLayoutSyncResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started -> StartCheckoutLayoutSyncResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): TeamcityCheckoutLayoutSyncJobState? = lifecycle.current()

    private fun buildCandidate(jobId: String): TeamcityCheckoutLayoutSyncJobState =
        TeamcityCheckoutLayoutSyncJobState(
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
        latestDiff: CheckoutLayoutDiffResult,
    ) {
        serviceEventRecorder.recordStart(
            type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_SYNC,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "TeamCity checkout layout sync running",
        )
        try {
            val result = syncService.sync(componentIds.toSet(), latestDiff, jobId, triggeredBy)
            lifecycle.update(jobId) { current -> current.copy(state = JobState.COMPLETED, finishedAt = Instant.now(), result = result) }
            LOG.info(
                "TeamCity checkout layout sync job {} COMPLETED: requested={}, applied={}, skipped={}, failed={}",
                jobId,
                result.requested,
                result.applied,
                result.skipped,
                result.failed,
            )
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_SYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary = "TeamCity checkout layout sync completed",
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
            LOG.error("TeamCity checkout layout sync job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current ->
                current.copy(state = JobState.FAILED, finishedAt = Instant.now(), errorMessage = e.message ?: e::class.java.simpleName)
            }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.TEAMCITY_CHECKOUT_LAYOUT_SYNC,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "TeamCity checkout layout sync failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcityCheckoutLayoutSyncJobServiceImpl::class.java)
    }
}
