package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * SYS-062 filter params for `GET /rest/api/4/admin/feedback`, both independently
 * optional and matched case-insensitively against the stored enum `name()`.
 */
data class FeedbackFilter(
    val type: String? = null,
    val status: String? = null,
)
