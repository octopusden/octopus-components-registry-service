package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant
import java.util.UUID

data class FieldOverrideCreateRequest(
    val fieldPath: String,
    val versionRange: String,
    val value: Any?,
)

data class FieldOverrideUpdateRequest(
    val versionRange: String? = null,
    val value: Any? = null,
)

data class FieldOverrideResponse(
    val id: UUID,
    val fieldPath: String,
    val versionRange: String,
    val value: Any?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)
