package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Separate Spring bean so per-commit writes run in their own transaction
 * (`REQUIRES_NEW`). Keeping this out of the main import loop ensures each
 * commit's rows are durable before the next commit is processed, which is
 * the v1 trade-off we accepted in lieu of full resume support.
 */
@Component
class GitHistoryCommitWriter(
    private val auditLogRepository: AuditLogRepository,
    private val stateRepository: GitHistoryImportStateRepository,
) : HistoryForceResetter {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persistCommitRows(rows: List<AuditLogEntity>) {
        if (rows.isEmpty()) return
        auditLogRepository.saveAll(rows)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveState(state: GitHistoryImportStateEntity) {
        state.updatedAt = Instant.now()
        stateRepository.save(state)
    }

    /**
     * Refresh `updated_at` on the IN_PROGRESS row without touching anything
     * else. Used as a liveness heartbeat by `runImport`'s chain loop so the
     * force-reset multi-pod-staleness check can distinguish "live import in
     * another pod" from "orphan claim from a crashed pod".
     *
     * Without this, `updated_at` would only flip at start (preflight insert
     * + targetSha fill) and at terminal markState — a long legitimate
     * import would appear stale to a force-reset operator after the
     * STALE_IN_PROGRESS_THRESHOLD elapsed.
     *
     * REQUIRES_NEW so the heartbeat write is committed even if the outer
     * chain loop's transaction (none currently, but defensive) rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun touchHeartbeat() {
        val row = stateRepository.findById(HISTORY_IMPORT_KEY).orElse(null) ?: return
        row.updatedAt = Instant.now()
        stateRepository.save(row)
    }

    /**
     * Atomic reset: deletes git-history audit rows and the state row
     * unless the state is IN_PROGRESS. Returning `false` signals that a
     * live import is still running and the caller should 409 instead of
     * stomping on its writes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resetIfNotInProgress(): Boolean {
        val existing = stateRepository.findById(HISTORY_IMPORT_KEY).orElse(null)
        if (existing != null && existing.status == GitHistoryImportStatus.IN_PROGRESS.name) {
            return false
        }
        auditLogRepository.deleteBySource("git-history")
        stateRepository.deleteAll()
        return true
    }

    /**
     * Unconditional wipe used by POST /admin/migrate-history/force-reset (A7.2).
     * Differs from [resetIfNotInProgress] in that it bypasses the IN_PROGRESS
     * guard — the controller is responsible for refusing this when an
     * in-memory job is RUNNING in the current pod. Idempotent: a no-op on an
     * empty DB returns normally so the endpoint can answer 204 either way.
     *
     * The destructive scope (state row + ALL git-history audit_log rows) is
     * load-bearing: the alternative — clearing only the state row — would
     * leak partial audit_log rows from an interrupted import, and the next
     * Run-without-reset would write fresh history on top of them, producing
     * duplicates. The force-reset confirm dialog spells this out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun forceReset() {
        auditLogRepository.deleteBySource("git-history")
        stateRepository.deleteAll()
    }
}
