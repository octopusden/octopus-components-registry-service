package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

/**
 * Compact projection used by `GET /api/4/components` list view. Surfaces only
 * the fields the Portal renders per row (badge + link).
 *
 * SYS-040 fields (`buildSystem`, `jiraProjectKey`, `vcsPath`, `teamcityProjectId`,
 * `teamcityProjectUrl`) are derived from the base configuration row + first
 * child (sort_order = 0) so multi-VCS / multi-TC components render their primary
 * link the same way single-target components do. Blank/empty strings are
 * normalized to null so callers can treat "absent" and "empty" alike.
 */
data class ComponentSummaryResponse(
    val id: UUID,
    val name: String,
    val displayName: String?,
    val componentOwner: String?,
    val system: String?,
    val productType: String?,
    val archived: Boolean,
    val canBeParent: Boolean = false,
    val updatedAt: Instant?,
    val labels: List<String> = emptyList(),
    val buildSystem: String? = null,
    val jiraProjectKey: String? = null,
    val vcsPath: String? = null,
    val teamcityProjectId: String? = null,
    val teamcityProjectUrl: String? = null,
)
