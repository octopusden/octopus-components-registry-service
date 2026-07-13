package org.octopusden.octopus.components.registry.server.controller

import jakarta.validation.Valid
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackFilter
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackOpenCountResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackResponse
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackStatusUpdateRequest
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.FeedbackService
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * SYS-062: admin triage side of user feedback. IMPORT_DATA-gated (class-level
 * `@permissionEvaluator.canImport()`), mirroring [ServiceEventControllerV4] and
 * [AdminControllerV4] — the portal Admin "Feedback" tab lives behind the same
 * operator permission. `IMPORT_DATA` is admin-only (ROLE_ADMIN); `ACCESS_AUDIT`
 * is deliberately NOT used here because viewers/editors hold it.
 *
 * Submit is a SEPARATE, authenticated-only controller ([FeedbackControllerV4]).
 */
@ConditionalOnDatabaseEnabled
@RestController
@RequestMapping("rest/api/4/admin/feedback")
@PreAuthorize("@permissionEvaluator.canImport()")
class FeedbackAdminControllerV4(
    private val feedbackService: FeedbackService,
    private val currentUserResolver: CurrentUserResolver,
) {
    /** Paginated submissions, newest first; optional exact `type`/`status` filters. */
    @GetMapping
    fun list(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) status: String?,
        @PageableDefault(size = DEFAULT_PAGE_SIZE) pageable: Pageable,
    ): Page<FeedbackResponse> = feedbackService.list(FeedbackFilter(type = type, status = status), pageable)

    /** Count of open (not RESOLVED) reports — drives the admin header badge. */
    @GetMapping("/open-count")
    fun openCount(): FeedbackOpenCountResponse = FeedbackOpenCountResponse(feedbackService.openCount())

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
    ): FeedbackResponse = feedbackService.get(id)

    /**
     * Screenshot bytes for one attachment. Served with the SERVER-normalized MIME +
     * `X-Content-Type-Options: nosniff` and an `inline` (not `attachment`)
     * Content-Disposition so the SPA can render it in an `<img>`. 404 if the
     * attachment does not belong to [id].
     */
    @GetMapping("/{id}/attachments/{attachmentId}")
    fun attachment(
        @PathVariable id: Long,
        @PathVariable attachmentId: Long,
    ): ResponseEntity<Resource> {
        val content =
            feedbackService.getAttachment(id, attachmentId)
                ?: throw NotFoundException("Attachment $attachmentId for feedback $id not found")
        val filename = content.filename?.takeIf { it.isNotBlank() } ?: "attachment-$attachmentId"
        val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType))
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''$encoded")
            .body(ByteArrayResource(content.data))
    }

    /** Transition a report's status; stamps `updated_by` from the JWT. */
    @PutMapping("/{id}/status")
    fun updateStatus(
        @PathVariable id: Long,
        @Valid @RequestBody request: FeedbackStatusUpdateRequest,
    ): FeedbackResponse {
        val status = requireNotNull(request.status) { "status is required" }
        return feedbackService.updateStatus(id, status, currentUserResolver.currentUsername())
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
    }
}
