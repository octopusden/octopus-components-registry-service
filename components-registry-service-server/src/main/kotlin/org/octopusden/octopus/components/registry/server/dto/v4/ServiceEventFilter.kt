package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant

/**
 * SYS-060 filter params for `GET /rest/api/4/admin/service-events`, all independently
 * optional. `from`/`to` form a half-open `[from, to)` window over `started_at`.
 */
data class ServiceEventFilter(
    val eventType: String? = null,
    /** SYSTEM / USER — matches every event_type in that category (case-insensitive). */
    val category: String? = null,
    val source: String? = null,
    val status: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
