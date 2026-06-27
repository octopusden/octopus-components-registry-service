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
    /**
     * Jira task key motivating the change, captured at save time. Optional —
     * null when the user supplied no key. Filterable via `audit/recent?jiraTaskKey=`.
     */
    val jiraTaskKey: String?,
    /** Free-text comment captured at save time. Optional — null when not supplied. */
    val changeComment: String?,
    /**
     * Human-readable component key for Component rows, resolved server-side from
     * the entityId UUID. Authoritative for field-override rows (whose value
     * snapshot carries no name) and stable across the snapshot's name/moduleName
     * divergence. Null for non-Component entity types or when the component can
     * no longer be resolved and the snapshot has no usable name.
     */
    val componentKey: String?,
)
