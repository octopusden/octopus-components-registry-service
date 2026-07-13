package org.octopusden.octopus.components.registry.server.dto.v4

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.octopusden.octopus.components.registry.server.service.FeedbackType

/**
 * SYS-062 — body of `POST /rest/api/4/feedback`. Any authenticated user may submit;
 * the submitter username is taken from the JWT server-side (never trusted from the
 * body). Screenshots ride along base64-encoded and are validated (magic bytes / size /
 * count) in the service before being stored as `bytea`.
 *
 * `type` is a strict enum (unknown value → 400 via the message-not-readable handler).
 * `@Size` on `dataBase64` is a cheap coarse guard; the exact per-file byte limit and
 * count cap are enforced in FeedbackService against FeedbackProperties.
 */
data class FeedbackCreateRequest(
    @field:NotNull(message = "type is required")
    val type: FeedbackType?,
    val title: String? = null,
    @field:NotBlank(message = "message must not be blank")
    val message: String = "",
    val pageUrl: String? = null,
    // Maps to VARCHAR(100); validate here so an oversized value is a clean 400, not a
    // DB-length error surfaced as 409 further down.
    @field:Size(max = 100, message = "appVersion must be at most 100 characters")
    val appVersion: String? = null,
    @field:Size(max = MAX_ATTACHMENTS, message = "at most $MAX_ATTACHMENTS attachments are allowed")
    @field:Valid
    val attachments: List<FeedbackAttachmentPayload>? = null,
) {
    companion object {
        /** Coarse count cap mirrored (authoritatively) by FeedbackProperties.maxAttachments. */
        const val MAX_ATTACHMENTS = 3
    }
}

/**
 * One screenshot on the way in. `contentType` here is the CLIENT's claim and is NOT
 * trusted — the service derives the real MIME from the decoded bytes' magic number.
 * `dataBase64` is the standard base64 of the raw image (the SPA strips the
 * `data:...;base64,` prefix before sending).
 */
data class FeedbackAttachmentPayload(
    val filename: String? = null,
    val contentType: String? = null,
    @field:NotBlank(message = "attachment data must not be blank")
    @field:Size(max = MAX_BASE64_CHARS, message = "attachment is too large")
    val dataBase64: String = "",
) {
    companion object {
        /**
         * Coarse compile-time ceiling (~5 MB of base64 ≈ ~3.7 MB decoded) so a huge
         * body is rejected before the service's exact, configurable byte check. The
         * authoritative per-file limit is FeedbackProperties.maxAttachmentBytes.
         */
        const val MAX_BASE64_CHARS = 5_000_000
    }
}
