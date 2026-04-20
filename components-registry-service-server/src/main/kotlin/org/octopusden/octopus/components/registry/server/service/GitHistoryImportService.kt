package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult

/**
 * One-shot backfill of git history into `audit_log` with
 * `source='git-history'`. No resume: a previous run in any terminal state
 * blocks the next call unless `reset=true` is passed.
 */
interface GitHistoryImportService {
    fun importHistory(
        toRef: String?,
        reset: Boolean,
    ): HistoryImportResult
}
