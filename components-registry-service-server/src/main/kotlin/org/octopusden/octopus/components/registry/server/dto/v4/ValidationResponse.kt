package org.octopusden.octopus.components.registry.server.dto.v4

/** Minimal validation finding for the UI: status + message. */
data class ValidationResponse(
    val status: String,
    val message: String? = null,
)
