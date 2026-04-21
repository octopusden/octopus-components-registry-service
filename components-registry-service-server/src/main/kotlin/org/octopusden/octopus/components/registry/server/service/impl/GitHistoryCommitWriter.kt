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
) {
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
     * Atomic reset: deletes git-history audit rows and the state row
     * unless the state is IN_PROGRESS. Returning `false` signals that a
     * live import is still running and the caller should 409 instead of
     * stomping on its writes.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resetIfNotInProgress(): Boolean {
        val existing = stateRepository.findById("component-history").orElse(null)
        if (existing != null && existing.status == GitHistoryImportStatus.IN_PROGRESS.name) {
            return false
        }
        auditLogRepository.deleteBySource("git-history")
        stateRepository.deleteAll()
        return true
    }
}
