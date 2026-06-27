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
 * `jiraTaskKey` and `changeComment` match the change metadata captured at save
 * time — both are **case-insensitive substring** matches (`%term%`) on
 * `audit_log.jira_task_key` / `audit_log.change_comment`. They drive the Portal's
 * audit search ("ABC" or "123" both find "ABC-123").
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
    val changeComment: String? = null,
)
