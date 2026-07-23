package org.octopusden.octopus.components.registry.server.dto.v4

import java.time.Instant

/**
 * Validation finding for the UI: stable [type] (e.g. `USES_OLD_JAVA_VERSION`) + [status] +
 * human-readable [message]. `type` lets the Portal identify, group, localize, or link a finding
 * instead of parsing presentation text; `updatedAt` lets it distinguish a fresh result from a
 * finding retained after a failed validation attempt.
 */
data class ValidationResponse(
    val type: String,
    val status: String,
    val message: String? = null,
    val updatedAt: Instant? = null,
)
