package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mirror of v4.json `AuditLogResponse` — a single audit row returned (paged) by
 * GET /rest/api/4/audit/recent and GET /rest/api/4/audit/{entityType}/{entityId}.
 *
 * `changeDiff`, `newValue` and `oldValue` are spec `object` maps with free-form `object` values,
 * modelled here as Map<String, JsonElement>. `changedAt` is an ISO-8601 date-time string (kept as
 * String to avoid pulling a date dependency into the DTO layer).
 *
 * Required per spec: action, changedAt, entityId, entityType, id, source.
 *
 * `componentKey` is the server-resolved human-readable key for Component rows
 * (SYS-054); null for non-Component rows or when unresolvable.
 */
@Serializable
data class AuditLogResponse(
    val id: Long,
    val action: String,
    val entityType: String,
    val entityId: String,
    val changedAt: String,
    val source: String,
    val componentKey: String? = null,
    val changedBy: String? = null,
    val correlationId: String? = null,
    val changeDiff: Map<String, JsonElement>? = null,
    val newValue: Map<String, JsonElement>? = null,
    val oldValue: Map<String, JsonElement>? = null,
)
