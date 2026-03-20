package org.octopusden.octopus.components.registry.server.event

data class AuditEvent(
    val entityType: String,
    val entityId: String,
    val action: String, // CREATE, UPDATE, DELETE
    val changedBy: String? = null,
    val oldValue: Map<String, Any?>? = null,
    val newValue: Map<String, Any?>? = null,
)
