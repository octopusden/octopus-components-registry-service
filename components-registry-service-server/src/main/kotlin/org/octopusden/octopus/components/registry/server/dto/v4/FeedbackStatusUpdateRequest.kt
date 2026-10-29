package org.octopusden.octopus.components.registry.server.dto.v4

import jakarta.validation.constraints.NotNull
import org.octopusden.octopus.components.registry.server.service.FeedbackStatus

/**
 * SYS-062 — body of `PUT /rest/api/4/admin/feedback/{id}/status`. Admin-only status
 * transition; the actor username is stamped into `updated_by` server-side.
 */
data class FeedbackStatusUpdateRequest(
    @field:NotNull(message = "status is required")
    val status: FeedbackStatus?,
)
