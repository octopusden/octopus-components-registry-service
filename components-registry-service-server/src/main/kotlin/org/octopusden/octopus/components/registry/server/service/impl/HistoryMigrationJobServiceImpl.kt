package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.service.HistoryImportProgressListener
import org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobService
import org.octopusden.octopus.components.registry.server.service.HistoryMigrationJobState
import org.octopusden.octopus.components.registry.server.service.HistoryRecoveryAction
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind
import org.octopusden.octopus.components.registry.server.service.StartHistoryMigrationResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private const val IMPORT_KEY = "component-history"

/**
 * In-memory async wrapper around [GitHistoryImportService.importHistory]. Mirrors
 * [MigrationJobServiceImpl] for the components flow — same gate integration, same
 * CAS-then-publish ordering, same finally-release.
 *
 * Difference from components: history has a table-backed claim
 * (`git_history_import_state`) that survives pod restarts. The in-memory state
 * is what the SPA sees while a job is RUNNING in this pod; after the pod
 * restarts the in-memory ref is empty but the DB row is still there, and
 * [current] synthesizes a state from it so the SPA can render the right action
 * (Retry on COMPLETED/FAILED, Force-reset on stale IN_PROGRESS — see A7.1).
 */
@Service
class HistoryMigrationJobServiceImpl(
    private val gitHistoryImportService: GitHistoryImportService,
    private val stateRepository: GitHistoryImportStateRepository,
    @Qualifier("migrationExecutor") private val executor: TaskExecutor,
    private val lifecycleGate: MigrationLifecycleGate,
) : HistoryMigrationJobService {
    private val state = AtomicReference<HistoryMigrationJobState?>()

    // See MigrationJobServiceImpl.startAsync — same CAS-retry shape, same suppression rationale.
    @Suppress("LoopWithTooManyJumpStatements")
    override fun startAsync(
        toRef: String?,
        reset: Boolean,
    ): StartHistoryMigrationResult {
        // Same ordering as MigrationJobServiceImpl.startAsync — see comments there.
        // Same-kind 409 first (so we don't read nullable state in cross-kind branch),
        // then cross-kind gate, then publish state, then submit.
        while (true) {
            val existing = state.get()
            if (existing?.state == JobState.RUNNING) {
                return StartHistoryMigrationResult(existing, isNewlyStarted = false)
            }

            val candidate =
                HistoryMigrationJobState(
                    id = UUID.randomUUID().toString(),
                    state = JobState.RUNNING,
                    startedAt = Instant.now(),
                    finishedAt = null,
                    totalCommits = 0,
                    processedCommits = 0,
                    auditRecords = 0,
                    skippedNoGroovy = 0,
                    skippedParseError = 0,
                    skippedUnknownNames = 0,
                    currentSha = null,
                    targetRef = null,
                    errorMessage = null,
                    result = null,
                )

            val gateConflict = lifecycleGate.tryClaim(JobKind.HISTORY, candidate.id)
            if (gateConflict != null) {
                when (gateConflict.kind) {
                    JobKind.HISTORY -> continue
                    JobKind.COMPONENTS -> throw MigrationConflictException(gateConflict)
                }
            }

            if (!state.compareAndSet(existing, candidate)) {
                lifecycleGate.release(candidate.id)
                continue
            }

            LOG.info("Starting history migration job {} (toRef={}, reset={})", candidate.id, toRef, reset)
            try {
                executor.execute { runHistoryMigration(candidate.id, toRef, reset) }
            } catch (
                @Suppress("TooGenericExceptionCaught") rejected: Throwable,
            ) {
                LOG.error("Failed to submit history migration job {} to executor", candidate.id, rejected)
                lifecycleGate.release(candidate.id)
                state.updateAndGet { current ->
                    if (current?.id != candidate.id) {
                        current
                    } else {
                        current.copy(
                            state = JobState.FAILED,
                            finishedAt = Instant.now(),
                            errorMessage =
                                "Failed to submit history migration: ${rejected.message ?: rejected::class.java.simpleName}",
                            recoveryAction = HistoryRecoveryAction.RETRY,
                        )
                    }
                }
                throw rejected
            }
            return StartHistoryMigrationResult(state.get() ?: candidate, isNewlyStarted = true)
        }
    }

    /**
     * In-memory state if a job is currently / recently active in THIS pod,
     * otherwise a [HistoryMigrationJobState] synthesized from the DB row so
     * the SPA can see prior-pod outcomes.
     *
     * IN_PROGRESS DB rows are surfaced as FAILED-with-explainer (NOT auto-FAILED
     * in the row itself) so a multi-pod overlap doesn't have one pod marking
     * another's live import as failed. The operator decides whether to
     * Force-reset.
     */
    override fun current(): HistoryMigrationJobState? {
        state.get()?.let { return it }
        val row = stateRepository.findById(IMPORT_KEY).orElse(null) ?: return null
        return synthesizeFromDb(row)
    }

    override fun clearInMemory() {
        state.set(null)
    }

    private fun runHistoryMigration(
        jobId: String,
        toRef: String?,
        reset: Boolean,
    ) {
        try {
            val result = gitHistoryImportService.importHistory(toRef, reset, progressListener(jobId))
            state.updateAndGet { current ->
                if (current?.id != jobId) {
                    current
                } else {
                    current.copy(
                        state = JobState.COMPLETED,
                        finishedAt = Instant.now(),
                        currentSha = null,
                        targetRef = result.targetRef,
                        totalCommits = result.processedCommits,
                        processedCommits = result.processedCommits,
                        auditRecords = result.auditRecords,
                        skippedNoGroovy = result.skippedNoGroovy,
                        skippedParseError = result.skippedParseError,
                        skippedUnknownNames = result.skippedUnknownNames,
                        result = result,
                        // Operator can re-run the import on top of a COMPLETED row
                        // via reset=true; SPA shows "Retry (reset state)".
                        recoveryAction = HistoryRecoveryAction.RETRY,
                    )
                }
            }
            LOG.info(
                "History migration job {} COMPLETED: {} commits processed, {} audit rows, {} ms",
                jobId,
                result.processedCommits,
                result.auditRecords,
                result.durationMs,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("History migration job {} FAILED", jobId, e)
            state.updateAndGet { current ->
                if (current?.id != jobId) {
                    current
                } else {
                    current.copy(
                        state = JobState.FAILED,
                        finishedAt = Instant.now(),
                        currentSha = null,
                        errorMessage = e.message ?: e::class.java.simpleName,
                        // FAILED in-memory paths are recoverable with reset=true
                        // (the underlying preflight will clear the FAILED row
                        // before re-claiming). FORCE_RESET is reserved for the
                        // synthesized stuck-IN_PROGRESS DB-fallback case only.
                        recoveryAction = HistoryRecoveryAction.RETRY,
                    )
                }
            }
        } finally {
            lifecycleGate.release(jobId)
        }
    }

    private fun progressListener(jobId: String): HistoryImportProgressListener =
        HistoryImportProgressListener { event ->
            state.updateAndGet { current ->
                if (current?.id != jobId) {
                    current
                } else {
                    current.copy(
                        totalCommits = event.totalCommits,
                        processedCommits = event.processedCommits,
                        auditRecords = event.auditRecords,
                        skippedNoGroovy = event.skippedNoGroovy,
                        skippedParseError = event.skippedParseError,
                        skippedUnknownNames = event.skippedUnknownNames,
                        currentSha = event.currentSha,
                        targetRef = event.targetRef,
                    )
                }
            }
        }

    private fun synthesizeFromDb(row: GitHistoryImportStateEntity): HistoryMigrationJobState {
        val targetRef = row.targetRef.takeIf { it.isNotEmpty() }
        // Synthetic id includes status + targetSha + updatedAt millis. Status
        // makes the id distinguishable across the COMPLETED→re-claimed→FAILED
        // sequence in the same pod-restart window; targetSha disambiguates
        // imports against different refs; updatedAt covers the rare
        // same-content same-ref case. The previous "restored-${epochMillis}"
        // form could collide on identical updatedAt (multiple sequential
        // restarts against the unchanged row), causing downstream consumers
        // that key on `id` to miss state transitions.
        val shaPart = row.targetSha.takeIf { it.isNotEmpty() } ?: "no-sha"
        val syntheticId = "restored:${row.status.lowercase()}:$shaPart:${row.updatedAt.toEpochMilli()}"
        return when (row.status) {
            GitHistoryImportStatus.COMPLETED.name ->
                synthesizedJobState(
                    syntheticId,
                    JobState.COMPLETED,
                    row.updatedAt,
                    targetRef,
                    errorMessage = null,
                    recoveryAction = HistoryRecoveryAction.RETRY,
                )
            GitHistoryImportStatus.FAILED.name ->
                synthesizedJobState(
                    syntheticId,
                    JobState.FAILED,
                    row.updatedAt,
                    targetRef,
                    errorMessage = "Previous run failed (details unavailable). Use Retry to re-run.",
                    recoveryAction = HistoryRecoveryAction.RETRY,
                )
            GitHistoryImportStatus.IN_PROGRESS.name ->
                synthesizedJobState(
                    syntheticId,
                    JobState.FAILED,
                    row.updatedAt,
                    targetRef,
                    // Human-readable explainer; the SPA does NOT match on this
                    // string anymore — it branches on `recoveryAction == FORCE_RESET`.
                    errorMessage =
                        "Previous run is stuck in IN_PROGRESS state with no active job in this pod " +
                            "(probably interrupted by a pod restart). Use Force reset to clear the claim, " +
                            "then Retry. WARNING: if another CRS pod is currently running this import, " +
                            "force-reset will corrupt its data.",
                    recoveryAction = HistoryRecoveryAction.FORCE_RESET,
                )
            else ->
                synthesizedJobState(
                    syntheticId,
                    JobState.FAILED,
                    row.updatedAt,
                    targetRef,
                    errorMessage = "Unknown status: ${row.status}. Contact operations.",
                    // Don't confidently route an unknown DB status to FORCE_RESET —
                    // we don't actually know if a force-reset is the right move.
                    // SPA renders the message and disables both action buttons.
                    recoveryAction = HistoryRecoveryAction.UNKNOWN,
                )
        }
    }

    private fun synthesizedJobState(
        id: String,
        state: JobState,
        updatedAt: Instant,
        targetRef: String?,
        errorMessage: String?,
        recoveryAction: HistoryRecoveryAction?,
    ): HistoryMigrationJobState =
        HistoryMigrationJobState(
            id = id,
            state = state,
            startedAt = updatedAt,
            finishedAt = updatedAt,
            totalCommits = 0,
            processedCommits = 0,
            auditRecords = 0,
            skippedNoGroovy = 0,
            skippedParseError = 0,
            skippedUnknownNames = 0,
            currentSha = null,
            targetRef = targetRef,
            errorMessage = errorMessage,
            result = null,
            recoveryAction = recoveryAction,
        )

    companion object {
        private val LOG = LoggerFactory.getLogger(HistoryMigrationJobServiceImpl::class.java)
    }
}
