package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant

/**
 * Filter object for `GET /rest/api/4/audit/recent`. Each field is independently
 * optional; combinations are ANDed in `AuditServiceImpl`. Empty filter is
 * equivalent to "all rows, newest first" — preserves the legacy behaviour of
 * the unfiltered `getRecentChanges` call.
 *
 * `from` and `to` form a half-open interval `[from, to)` over `audit_log.changed_at`.
 * Either bound is independently optional.
 *
 * Contract: `SYS-036` in `requirements-common.md`.
 */
data class AuditLogFilter(
    val entityType: String? = null,
    val entityId: String? = null,
    val changedBy: String? = null,
    val source: String? = null,
    val action: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
