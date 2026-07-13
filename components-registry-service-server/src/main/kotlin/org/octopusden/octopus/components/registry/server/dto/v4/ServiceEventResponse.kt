package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.entity.ServiceEventEntity
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import java.time.Instant

/**
 * SYS-060 — wire shape of one operational service-event row, served by
 * `GET /rest/api/4/admin/service-events`. camelCase (matches [AuditLogResponse]),
 * consumed by the portal Admin "Events" tab.
 */
data class ServiceEventResponse(
    val id: Long,
    val eventType: String,
    /** Derived SYSTEM/USER split (from eventType) so the SPA can separate the two views. */
    val category: String,
    val status: String,
    val source: String,
    val triggeredBy: String?,
    val serviceVersion: String?,
    val correlationId: String?,
    val summary: String?,
    val detail: Map<String, Any?>?,
    val startedAt: Instant,
    val finishedAt: Instant?,
) {
    companion object {
        fun from(entity: ServiceEventEntity): ServiceEventResponse =
            ServiceEventResponse(
                // A persisted row always has an id; fail loud rather than emit a misleading 0.
                id = requireNotNull(entity.id) { "persisted service_event row has a null id" },
                eventType = entity.eventType,
                category = ServiceEventType.categoryOf(entity.eventType).name,
                status = entity.status,
                source = entity.source,
                triggeredBy = entity.triggeredBy,
                serviceVersion = entity.serviceVersion,
                correlationId = entity.correlationId,
                summary = entity.summary,
                detail = entity.detail,
                startedAt = entity.startedAt,
                finishedAt = entity.finishedAt,
            )
    }
}
