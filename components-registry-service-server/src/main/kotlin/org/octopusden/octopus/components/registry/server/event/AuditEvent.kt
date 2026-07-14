package org.octopusden.octopus.components.registry.server.event

data class AuditEvent(
    val entityType: String,
    val entityId: String,
    val action: String, // CREATE, UPDATE, DELETE
    val changedBy: String? = null,
    val oldValue: Map<String, Any?>? = null,
    val newValue: Map<String, Any?>? = null,
    // Optional change metadata captured at save time (component create/update);
    // null for internal/cascade events that carry no user-supplied context.
    val jiraTaskKey: String? = null,
    val changeComment: String? = null,
    // Shared across every audit row written in the same save/transaction, so the
    // audit history can group them into one record (see AuditCorrelationIdProvider).
    val correlationId: String? = null,
)
