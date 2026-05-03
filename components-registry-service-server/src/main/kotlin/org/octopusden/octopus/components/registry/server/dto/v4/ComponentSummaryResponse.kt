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
    // null when the source nested entity is absent or its leaf field is null.
    val buildSystem: String? = null,
    val jiraProjectKey: String? = null,
    val vcsPath: String? = null,
)
