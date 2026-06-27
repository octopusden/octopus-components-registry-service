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
 * `includeMigrated` controls visibility of git-history baseline rows
 * (`action = MIGRATED`). It defaults to `false` so migration noise is hidden;
 * an explicit `action = MIGRATED` filter always wins regardless of this flag
 * (SYS-049).
 *
 * `jiraTaskKey` matches the change-metadata key captured at save time (exact
 * match on `audit_log.jira_task_key`) — drives the Portal's "search by task key".
 *
 * Contract: `SYS-036`, `SYS-049` in `requirements-common.md`.
 */
data class AuditLogFilter(
    val entityType: String? = null,
    val entityId: String? = null,
    val changedBy: String? = null,
    val source: String? = null,
    val action: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val includeMigrated: Boolean = false,
    val jiraTaskKey: String? = null,
)
