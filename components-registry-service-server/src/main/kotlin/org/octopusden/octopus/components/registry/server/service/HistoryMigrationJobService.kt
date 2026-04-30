package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import java.time.Instant

/**
 * Recovery hint for a non-running history-migration state. Drives the SPA's
 * action button mode. Replaces the previous "match `errorMessage.includes('marked
 * IN_PROGRESS')`" substring contract that was silently coupled across two repos.
 *
 * - `RETRY` — terminal-but-recoverable: COMPLETED or normal FAILED row. The SPA
 *   shows "Retry (reset state)" and POSTs with `reset=true`.
 * - `FORCE_RESET` — stuck IN_PROGRESS row (left by a previous pod that crashed
 *   or restarted mid-import). SPA shows "Force reset" + disabled "Retry".
 * - `UNKNOWN` — the backend has a state row it can't classify (e.g. a future
 *   status enum value rolled out via DB without a code change). The SPA shows
 *   the errorMessage with NO action buttons enabled — defense against
 *   confidently telling the operator "Force reset" or "Retry" against a
 *   state we don't actually understand.
 *
 * `null` for RUNNING jobs and on idle (no claim, no previous run).
 */
enum class HistoryRecoveryAction { RETRY, FORCE_RESET, UNKNOWN }

/**
 * Server-side state of a long-running POST /rest/api/4/admin/migrate-history run.
 *
 * Mirrors [MigrationJobState] for components, with history-specific counters
 * (`processedCommits/totalCommits`, `auditRecords`, etc.) instead of per-component.
 *
 * Unlike the components job, history has a table-backed claim
 * (`git_history_import_state`) that persists across pod restarts. The in-memory
 * state lives only for the duration of the running pod; on restart, the DB row
 * is the source of truth and the impl synthesizes a state from it (see A7.1).
 */
data class HistoryMigrationJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    /** Total commits in the first-parent chain. Known once RevWalk finishes; 0 before that. */
    val totalCommits: Int,
    val processedCommits: Int,
    val auditRecords: Int,
    val skippedNoGroovy: Int,
    val skippedParseError: Int,
    val skippedUnknownNames: Int,
    /** Short SHA of the commit currently being processed; null at boundaries. */
    val currentSha: String?,
    /** Resolved target ref (e.g. "refs/tags/components-registry-1.2"); null until resolved. */
    val targetRef: String?,
    val errorMessage: String?,
    val result: HistoryImportResult?,
    /** See [HistoryRecoveryAction]. Null while RUNNING or for an idle service. */
    val recoveryAction: HistoryRecoveryAction? = null,
)

data class StartHistoryMigrationResult(
    val state: HistoryMigrationJobState,
    val isNewlyStarted: Boolean,
)

/**
 * Outcome of [HistoryMigrationJobService.forceReset].
 *
 * Using a sealed type (rather than throwing) so the service layer can express
 * all outcomes without coupling the controller to implementation details:
 *  - [Cleared] → 204 No Content; DB row + audit rows wiped (or already empty).
 *  - [Blocked] → 409 Conflict; controller maps [code]/[message]/[activeKind]/[activeJobId]
 *    to a [org.octopusden.octopus.components.registry.server.dto.v4.MigrationConflictResponse].
 */
sealed interface ForceResetOutcome {
    data object Cleared : ForceResetOutcome

    data class Blocked(
        val code: String,
        val message: String,
        val activeKind: String,
        val activeJobId: String?,
    ) : ForceResetOutcome
}

interface HistoryMigrationJobService {
    /**
     * Mirror of [MigrationJobService.startAsync] for history. Same idempotency
     * contract: a second startAsync while one is RUNNING attaches; same
     * cross-job semantics through [MigrationLifecycleGate].
     *
     * `toRef` and `reset` are passed through to the underlying
     * [GitHistoryImportService.importHistory]:
     *  - `toRef` defaults to the auto-resolved tag matching the configured prefix;
     *  - `reset=true` is required to re-run on top of a terminal `git_history_import_state` row.
     */
    fun startAsync(
        toRef: String?,
        reset: Boolean,
    ): StartHistoryMigrationResult

    /**
     * Latest known job state. Falls back to a [HistoryMigrationJobState] synthesized
     * from `git_history_import_state` if no in-memory job exists (so the SPA sees
     * COMPLETED / FAILED / restored-FAILED state after a pod restart and can
     * surface the right action — see A7.1).
     */
    fun current(): HistoryMigrationJobState?

    /**
     * Drop the in-memory state. Called externally when an operator-level
     * action (e.g. a test helper) needs to reset the slot without going
     * through the full guard logic. Ignored (with a WARN log) if a job is
     * currently RUNNING — callers that need to clear a live job should use
     * [forceReset] instead.
     */
    fun clearInMemory()

    /**
     * Destructive recovery endpoint (POST /admin/migrate-history/force-reset).
     *
     * Applies two guards before wiping:
     *  1. In-pod gate — refused with [ForceResetOutcome.Blocked] if a history
     *     migration is currently RUNNING in this pod.
     *  2. Cross-pod staleness — refused if the DB row is IN_PROGRESS with a
     *     fresh `updatedAt` (< STALE_IN_PROGRESS_THRESHOLD), unless
     *     `ackMultipodRisk=true` overrides.
     *
     * After both guards pass it calls the underlying commit writer to wipe the
     * state row + all audit_log rows with source='git-history', then clears
     * the in-memory slot.
     *
     * Returns [ForceResetOutcome.Cleared] (idempotent) when the DB was already
     * empty, so the controller can always answer 204 on success.
     */
    fun forceReset(ackMultipodRisk: Boolean): ForceResetOutcome
}
