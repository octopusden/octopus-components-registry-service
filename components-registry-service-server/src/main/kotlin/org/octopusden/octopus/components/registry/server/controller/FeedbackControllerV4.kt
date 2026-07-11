package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackResponse
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.FeedbackService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * SYS-062: submit side of user feedback. Open to ANY authenticated user — the filter
 * chain rule for v4 writes maps to `authenticated()` and rejects anonymous, and the
 * submitter username is taken from the JWT (never the body). Admin reads/triage live in the
 * separate IMPORT_DATA-gated [FeedbackAdminControllerV4] under `/admin/feedback`.
 *
 * A `413` here is produced by [org.octopusden.octopus.components.registry.server.config.FeedbackRequestSizeFilter]
 * (body-size guard), which runs before this controller.
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/feedback")
class FeedbackControllerV4(
    private val feedbackService: FeedbackService,
    private val currentUserResolver: CurrentUserResolver,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Feedback recorded"),
        ApiResponse(responseCode = "400", description = "Invalid body / attachment (not an image, too large, bad base64)"),
        ApiResponse(responseCode = "401", description = "Unauthenticated"),
        ApiResponse(responseCode = "413", description = "Request body exceeds the size cap"),
    )
    fun submit(
        @Valid @RequestBody request: FeedbackCreateRequest,
        @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): FeedbackResponse = feedbackService.submit(request, currentUserResolver.currentUsername(), userAgent)
}
