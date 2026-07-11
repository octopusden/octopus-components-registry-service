package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackFilter
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * SYS-062: user feedback submission + admin triage. Submit is open to any
 * authenticated user; list/get/status/attachment reads are admin-only (IMPORT_DATA),
 * enforced at the controller.
 */
interface FeedbackService {
    /** Persist a submission by [submittedBy] (JWT username); validates + stores screenshots. */
    fun submit(
        request: FeedbackCreateRequest,
        submittedBy: String,
        userAgent: String?,
    ): FeedbackResponse

    /** Paginated submissions matching [filter], newest first by `created_at` when unsorted. */
    fun list(
        filter: FeedbackFilter,
        pageable: Pageable,
    ): Page<FeedbackResponse>

    /** One submission with attachment metadata, or throws NotFoundException. */
    fun get(id: Long): FeedbackResponse

    /** Transition [id] to [status], stamping `updated_by`=[updatedBy]. NotFoundException if absent. */
    fun updateStatus(
        id: Long,
        status: FeedbackStatus,
        updatedBy: String,
    ): FeedbackResponse

    /** Screenshot bytes for an attachment scoped to its parent feedback, or null (→ 404). */
    fun getAttachment(
        feedbackId: Long,
        attachmentId: Long,
    ): FeedbackAttachmentContent?

    /** Retention prune of RESOLVED reports older than the configured window. Returns rows deleted. */
    fun prune(): Int
}

/** Screenshot bytes + the server-normalized MIME/filename, for the attachment endpoint. */
data class FeedbackAttachmentContent(
    val filename: String?,
    val contentType: String,
    val data: ByteArray,
)
