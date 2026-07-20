package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

/** A finding joined to an owning component (admin dashboard). Every row is an issue (WARNING/ERROR). */
data class ComponentTeamcityValidationRow(
    val componentId: UUID,
    val componentName: String,
    val projectId: String,
    val type: String,
    val status: String,
    val message: String?,
    val updatedAt: Instant,
)
