package org.octopusden.octopus.components.registry.server.event

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.util.AuditDiff
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@ConditionalOnDatabaseEnabled
@Component
class AuditEventListener(
    private val auditLogRepository: AuditLogRepository,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleAuditEvent(event: AuditEvent) {
        val changeDiff = AuditDiff.compute(event.oldValue, event.newValue)

        // Suppress no-op updates: when both snapshots are present but nothing
        // actually changed, AuditDiff yields null. Persisting such a row clutters
        // the audit log with meaningless "saved, changed nothing" entries (e.g. a
        // Save click on an unmodified form). CREATE (null oldValue) and DELETE
        // (null newValue) legitimately produce a null diff and must still be kept.
        if (event.oldValue != null && event.newValue != null && changeDiff == null) {
            return
        }

        val auditLog =
            AuditLogEntity(
                entityType = event.entityType,
                entityId = event.entityId,
                action = event.action,
                changedBy = event.changedBy,
                oldValue = event.oldValue,
                newValue = event.newValue,
                changeDiff = changeDiff,
            )
        auditLogRepository.save(auditLog)
    }
}
