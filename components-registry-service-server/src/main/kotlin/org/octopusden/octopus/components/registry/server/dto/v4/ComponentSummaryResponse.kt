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
)
