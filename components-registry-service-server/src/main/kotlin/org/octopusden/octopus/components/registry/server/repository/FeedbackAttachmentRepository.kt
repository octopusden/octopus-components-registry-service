package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.FeedbackAttachmentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * SYS-062: fetch a single screenshot's bytes. [findByIdAndFeedback_Id] scopes the
 * lookup to the parent feedback (nested property `feedback.id`) so a caller cannot
 * read an attachment by guessing its id under an unrelated feedback id (null → 404).
 */
@Repository
interface FeedbackAttachmentRepository : JpaRepository<FeedbackAttachmentEntity, Long> {
    @Suppress("FunctionNaming") // Spring Data nested-property path: feedback.id
    fun findByIdAndFeedback_Id(
        id: Long,
        feedbackId: Long,
    ): FeedbackAttachmentEntity?
}
