package org.octopusden.octopus.components.registry.server.teamcity.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.service.AsyncJobLifecycle
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind
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

    override fun startAsync(): StartTeamcitySyncResult {
        val outcome =
            lifecycle.claimAndSubmit(
                buildCandidate = ::buildCandidate,
                work = ::runResync,
            )
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

    private fun runResync(jobId: String) {
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
        } finally {
            lifecycle.release(jobId)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeamcitySyncJobServiceImpl::class.java)
    }
}
