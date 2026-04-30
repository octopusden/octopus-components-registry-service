package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.octopusden.octopus.components.registry.server.service.ForceResetOutcome
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
    private val forceResetter: HistoryForceResetter,
) : HistoryMigrationJobService {
    private val state = AtomicReference<HistoryMigrationJobState?>()

    /** Mirror of [MigrationJobServiceImpl.ClaimAttempt] for the history flow. */
    private sealed interface ClaimAttempt {
        data class Attached(
            val state: HistoryMigrationJobState,
        ) : ClaimAttempt

        data class CrossKindConflict(
            val active: MigrationLifecycleGate.ActiveJob,
        ) : ClaimAttempt

        data object Retry : ClaimAttempt

        data class Claimed(
            val candidate: HistoryMigrationJobState,
        ) : ClaimAttempt
    }

    override fun startAsync(
        toRef: String?,
        reset: Boolean,
    ): StartHistoryMigrationResult {
        while (true) {
            when (val attempt = tryClaim()) {
                is ClaimAttempt.Attached -> return StartHistoryMigrationResult(attempt.state, isNewlyStarted = false)
                is ClaimAttempt.CrossKindConflict -> throw MigrationConflictException(attempt.active)
                is ClaimAttempt.Claimed -> {
                    submitToExecutor(attempt.candidate, toRef, reset)
                    return StartHistoryMigrationResult(state.get() ?: attempt.candidate, isNewlyStarted = true)
                }
                ClaimAttempt.Retry -> Unit
            }
        }
    }

    /** See [MigrationJobServiceImpl.tryClaim] — same CAS-retry shape, same ordering rationale. */
    private fun tryClaim(): ClaimAttempt {
        val existing = state.get()
        if (existing?.state == JobState.RUNNING) {
            return ClaimAttempt.Attached(existing)
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
            return when (gateConflict.kind) {
                JobKind.HISTORY -> ClaimAttempt.Retry
                JobKind.COMPONENTS -> ClaimAttempt.CrossKindConflict(gateConflict)
            }
        }

        if (!state.compareAndSet(existing, candidate)) {
            lifecycleGate.release(candidate.id)
            return ClaimAttempt.Retry
        }
        return ClaimAttempt.Claimed(candidate)
    }

    @Suppress("TooGenericExceptionCaught") // Throwable so executor rejection ALWAYS unwinds the slot.
    private fun submitToExecutor(
        candidate: HistoryMigrationJobState,
        toRef: String?,
        reset: Boolean,
    ) {
        LOG.info("Starting history migration job {} (toRef={}, reset={})", candidate.id, toRef, reset)
        try {
            executor.execute { runHistoryMigration(candidate.id, toRef, reset) }
        } catch (rejected: Throwable) {
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
        val row = stateRepository.findById(HISTORY_IMPORT_KEY).orElse(null) ?: return null
        return synthesizeFromDb(row)
    }

    override fun clearInMemory() {
        val current = state.get()
        if (current?.state == JobState.RUNNING) {
            LOG.warn(
                "clearInMemory() called while job {} is RUNNING — ignored. " +
                    "Call forceReset() or wait for the job to complete first.",
                current.id,
            )
            return
        }
        state.set(null)
    }

    /**
     * Guards → wipe → return outcome. See [HistoryMigrationJobService.forceReset].
     *
     * Guard ordering mirrors [AdminControllerV4.forceResetHistory] before refactor:
     *  1. In-pod RUNNING check (in-memory read, no DB).
     *  2. DB staleness check (DB read + age compare).
     *  3. TOCTOU re-read: re-check in-memory state after DB read to catch a
     *     concurrent [startAsync] that claimed the gate between Guards 1 and 2.
     */
    override fun forceReset(ackMultipodRisk: Boolean): ForceResetOutcome {
        // Guard 1: same-pod active job.
        val active = state.get()
        if (active?.state == JobState.RUNNING) {
            return ForceResetOutcome.Blocked(
                code = "history-migration-running",
                message =
                    "Cannot force-reset while history migration is RUNNING (jobId=${active.id}). " +
                        "Wait for it to finish or restart the pod.",
                activeKind = JobKind.HISTORY.name,
                activeJobId = active.id,
            )
        }

        // Guard 2: cross-pod staleness. Inspect the DB row directly so a live import
        // in another pod (with a fresh updatedAt) is detected.
        val row = stateRepository.findById(HISTORY_IMPORT_KEY).orElse(null)
        if (row != null &&
            row.status == GitHistoryImportStatus.IN_PROGRESS.name &&
            !ackMultipodRisk
        ) {
            val age = Duration.between(row.updatedAt, Instant.now())
            if (age < STALE_IN_PROGRESS_THRESHOLD) {
                LOG.info(
                    "Refusing force-reset: IN_PROGRESS row updated {} ago (threshold={}). " +
                        "Likely a live import in another pod. Override with ack-multipod-risk=true if you accept the data-corruption risk.",
                    age,
                    STALE_IN_PROGRESS_THRESHOLD,
                )
                return ForceResetOutcome.Blocked(
                    code = "history-import-likely-live-elsewhere",
                    message =
                        "Refusing force-reset: the IN_PROGRESS claim was updated ${age.toSeconds()}s ago, " +
                            "below the ${STALE_IN_PROGRESS_THRESHOLD.toMinutes()}-minute staleness threshold. " +
                            "A live import in another pod is likely. " +
                            "If you understand the multi-pod risk and want to proceed anyway, " +
                            "POST again with ?ack-multipod-risk=true.",
                    activeKind = JobKind.HISTORY.name,
                    activeJobId = null,
                )
            }
        }

        // TOCTOU re-read: re-check in-memory state after the DB row read. The window
        // between Guard 1 and this point is at most one DB round-trip wide. A concurrent
        // startAsync that slipped past Guard 1 (it hadn't claimed the gate yet) and has
        // since published its state will be caught here.
        val activeAfterRowRead = state.get()
        if (activeAfterRowRead?.state == JobState.RUNNING) {
            return ForceResetOutcome.Blocked(
                code = "history-migration-running",
                message =
                    "Cannot force-reset while history migration is RUNNING (jobId=${activeAfterRowRead.id}). " +
                        "Wait for it to finish or restart the pod.",
                activeKind = JobKind.HISTORY.name,
                activeJobId = activeAfterRowRead.id,
            )
        }

        // Audit-loud destructive action: log before the wipe so the pre-state survives
        // in the log stream. ackMultipodRisk override logged at ERROR so alert pipelines
        // that filter on ERROR-only catch it.
        if (row != null) {
            val msg =
                "FORCE-RESET: deleting git_history_import_state " +
                    "(target=${row.targetRef}@${row.targetSha}, status=${row.status}, " +
                    "updatedAt=${row.updatedAt}) and all audit_log rows with source='git-history'."
            if (ackMultipodRisk) LOG.error("$msg [ack-multipod-risk=true overriding staleness check]") else LOG.info(msg)
        } else {
            LOG.info("FORCE-RESET: no state row to delete (idempotent no-op on empty DB)")
        }

        forceResetter.forceReset()
        // Bypass the clearInMemory() RUNNING guard — we already verified there is no
        // RUNNING job above (Guards 1, 3). Direct set avoids the redundant re-check.
        state.set(null)
        return ForceResetOutcome.Cleared
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
