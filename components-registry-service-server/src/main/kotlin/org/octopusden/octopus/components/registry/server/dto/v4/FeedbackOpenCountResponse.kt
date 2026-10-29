package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * SYS-062 — number of "open" feedback reports (not RESOLVED), for the admin header badge.
 */
data class FeedbackOpenCountResponse(
    val open: Long,
)
