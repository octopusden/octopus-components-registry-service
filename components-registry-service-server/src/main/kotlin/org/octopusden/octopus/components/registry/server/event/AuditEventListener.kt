package org.octopusden.octopus.components.registry.server.event

import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AuditEventListener(
    private val auditLogRepository: AuditLogRepository,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun handleAuditEvent(event: AuditEvent) {
        val diff = computeDiff(event.oldValue, event.newValue)
        val auditLog =
            AuditLogEntity(
                entityType = event.entityType,
                entityId = event.entityId,
                action = event.action,
                changedBy = event.changedBy,
                oldValue = event.oldValue,
                newValue = event.newValue,
                changeDiff = diff,
            )
        auditLogRepository.save(auditLog)
    }

    private fun computeDiff(
        old: Map<String, Any?>?,
        new: Map<String, Any?>?,
    ): Map<String, Any?>? {
        if (old == null || new == null) return null
        val diff = mutableMapOf<String, Any?>()
        val allKeys = old.keys + new.keys
        for (key in allKeys) {
            val oldVal = old[key]
            val newVal = new[key]
            if (oldVal != newVal) {
                diff[key] = mapOf("old" to oldVal, "new" to newVal)
            }
        }
        return diff.ifEmpty { null }
    }
}
