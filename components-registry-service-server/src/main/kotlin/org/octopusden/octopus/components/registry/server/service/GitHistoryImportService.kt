package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult

/**
 * One-shot backfill of git history into `audit_log` with
 * `source='git-history'`. No resume: a previous run in any terminal state
 * blocks the next call unless `reset=true` is passed.
 */
interface GitHistoryImportService {
    /**
     * Run an import without progress reporting. Equivalent to passing
     * [HistoryImportProgressListener.NOOP] to the overload below.
     */
    fun importHistory(
        toRef: String?,
        reset: Boolean,
    ): HistoryImportResult = importHistory(toRef, reset, HistoryImportProgressListener.NOOP)

    /**
     * Run an import and feed live progress events to [listener] — one event
     * before the per-commit loop starts (so the SPA learns `totalCommits`
     * without waiting for the first commit), one per commit (so the bar
     * advances smoothly), and one final event before return (clears
     * `currentSha` so the SPA stops showing a stale "Processing commit X").
     *
     * Listener exceptions are caught and logged but do not abort the import —
     * progress reporting is observability, not control flow.
     */
    fun importHistory(
        toRef: String?,
        reset: Boolean,
        listener: HistoryImportProgressListener,
    ): HistoryImportResult
}

/**
 * Progress sink for [GitHistoryImportService.importHistory]. Drives the SPA's
 * History migration progress bar.
 */
fun interface HistoryImportProgressListener {
    fun onProgress(event: HistoryImportProgressEvent)

    companion object {
        val NOOP: HistoryImportProgressListener = HistoryImportProgressListener { }
    }
}

data class HistoryImportProgressEvent(
    /** Total commits in the first-parent chain. Known after RevWalk; 0 only at the very first emission moment. */
    val totalCommits: Int,
    /** Commits processed so far (post-loop iteration); starts at 0, ends at totalCommits. */
    val processedCommits: Int,
    /** audit_log rows written so far. Climbs cumulatively as commits are persisted. */
    val auditRecords: Int,
    val skippedNoGroovy: Int,
    val skippedParseError: Int,
    val skippedUnknownNames: Int,
    /** Short SHA of the commit currently being processed; null at boundaries. */
    val currentSha: String?,
    /** Resolved target ref the import is walking toward. */
    val targetRef: String,
)
