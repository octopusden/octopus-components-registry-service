package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant

data class AuditLogResponse(
    val id: Long,
    val entityType: String,
    val entityId: String,
    val action: String,
    val changedBy: String?,
    val changedAt: Instant,
    val oldValue: Map<String, Any?>?,
    val newValue: Map<String, Any?>?,
    val changeDiff: Map<String, Any?>?,
    val correlationId: String?,
    val source: String,
)
