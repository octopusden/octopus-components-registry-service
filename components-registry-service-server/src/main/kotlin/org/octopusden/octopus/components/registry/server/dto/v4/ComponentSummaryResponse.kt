package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

data class ComponentSummaryResponse(
    val id: UUID,
    val name: String,
    val displayName: String?,
    val componentOwner: String?,
    val system: Set<String>,
    val productType: String?,
    val archived: Boolean,
    val updatedAt: Instant?,
    // SYS-040: list-view extras. Derived from nested entities (first row only —
    // list view shows one badge / one link per component, not the full set).
    // null in three cases: (1) the parent nested entity is absent, (2) the
    // leaf field is null, (3) the leaf field is a blank/empty string —
    // normalized to null by the v4 mapper so the Portal can treat
    // absence and empty alike (no link rendered).
    val buildSystem: String? = null,
    val jiraProjectKey: String? = null,
    val vcsPath: String? = null,
)
