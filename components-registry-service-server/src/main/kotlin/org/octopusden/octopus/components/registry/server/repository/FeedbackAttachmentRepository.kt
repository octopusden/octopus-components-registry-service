package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.FeedbackAttachmentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * SYS-062: fetch a single screenshot's bytes. [findByIdAndFeedbackId] scopes the lookup to
 * the parent feedback so a caller cannot read an attachment by guessing its id under an
 * unrelated feedback id (null → 404). An explicit `@Query` (rather than a derived
 * `findByIdAndFeedback_Id`) keeps the method name camelCase for the ktlint function-naming rule.
 */
@Repository
interface FeedbackAttachmentRepository : JpaRepository<FeedbackAttachmentEntity, Long> {
    @Query("SELECT a FROM FeedbackAttachmentEntity a WHERE a.id = :id AND a.feedback.id = :feedbackId")
    fun findByIdAndFeedbackId(
        @Param("id") id: Long,
        @Param("feedbackId") feedbackId: Long,
    ): FeedbackAttachmentEntity?
}
