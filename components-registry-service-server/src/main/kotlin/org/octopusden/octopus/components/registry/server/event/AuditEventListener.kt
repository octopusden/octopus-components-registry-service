package org.octopusden.octopus.components.registry.server.event

import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.util.AuditDiff
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AuditEventListener(
    private val auditLogRepository: AuditLogRepository,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleAuditEvent(event: AuditEvent) {
        val auditLog =
            AuditLogEntity(
                entityType = event.entityType,
                entityId = event.entityId,
                action = event.action,
                changedBy = event.changedBy,
                oldValue = event.oldValue,
                newValue = event.newValue,
                changeDiff = AuditDiff.compute(event.oldValue, event.newValue),
            )
        auditLogRepository.save(auditLog)
    }
}
