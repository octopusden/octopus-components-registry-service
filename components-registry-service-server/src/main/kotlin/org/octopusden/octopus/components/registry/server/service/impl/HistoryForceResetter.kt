package org.octopusden.octopus.components.registry.server.service.impl

/**
 * Narrow interface for the single destructive operation extracted from
 * [GitHistoryCommitWriter] so that [HistoryMigrationJobServiceImpl] can depend on
 * this contract without pulling in the full commit-writer bean (and its
 * `AuditLogRepository` dep) into unit tests.
 *
 * Only [GitHistoryCommitWriter] implements this in production. Tests use a lambda stub.
 */
fun interface HistoryForceResetter {
    /**
     * Unconditional wipe: deletes all `audit_log` rows with `source='git-history'`
     * and the `git_history_import_state` row. Idempotent on an empty DB.
     */
    fun forceReset()
}
