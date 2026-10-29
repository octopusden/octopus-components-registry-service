package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.entity.FeedbackEntity
import java.time.Instant

/**
 * SYS-062 — wire shape of one feedback submission for the admin views. camelCase,
 * consumed by the portal Admin "Feedback" tab. Attachment [attachments] carries only
 * METADATA (no bytes); the SPA renders each screenshot via the attachment-bytes
 * endpoint `GET /rest/api/4/admin/feedback/{id}/attachments/{attachmentId}`.
 */
data class FeedbackResponse(
    val id: Long,
    val type: String,
    val status: String,
    val title: String?,
    val message: String,
    val submittedBy: String?,
    val pageUrl: String?,
    val appVersion: String?,
    val detail: Map<String, Any?>?,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val updatedBy: String?,
    val attachments: List<FeedbackAttachmentMeta>,
) {
    companion object {
        /**
         * Build from a persisted row + its already-projected attachment metadata.
         * The caller supplies [attachmentMeta] from the no-`data` projection so this
         * never touches the entity's lazy `attachments` collection (no blob fetch).
         */
        fun from(
            entity: FeedbackEntity,
            attachmentMeta: List<FeedbackAttachmentMeta>,
        ): FeedbackResponse =
            FeedbackResponse(
                id = requireNotNull(entity.id) { "persisted feedback row has a null id" },
                type = entity.type,
                status = entity.status,
                title = entity.title,
                message = entity.message,
                submittedBy = entity.submittedBy,
                pageUrl = entity.pageUrl,
                appVersion = entity.appVersion,
                detail = entity.detail,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                updatedBy = entity.updatedBy,
                attachments = attachmentMeta,
            )
    }
}

/** Attachment metadata (no bytes) surfaced in [FeedbackResponse]. */
data class FeedbackAttachmentMeta(
    val id: Long,
    val filename: String?,
    val contentType: String?,
    val sizeBytes: Int?,
)
