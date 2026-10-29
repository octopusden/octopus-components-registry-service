package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.FeedbackEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * SYS-062: read/write access to feedback submissions.
 *
 * The admin list is built with `findAll(spec, pageable)` over the SCALAR feedback
 * columns only — the mapper never touches [FeedbackEntity.attachments] (that would
 * fetch every screenshot `bytea`). Attachment metadata for a page is pulled in ONE
 * query via [findAttachmentMetaByFeedbackIds], which selects no `data` column.
 */
@Repository
interface FeedbackRepository :
    JpaRepository<FeedbackEntity, Long>,
    JpaSpecificationExecutor<FeedbackEntity> {
    /**
     * Attachment metadata (no `data` blob) for the given feedback ids, in one query.
     * Returned flat and grouped in memory by the service, so the list/detail views
     * report attachment counts/names without hydrating any screenshot bytes.
     */
    @Query(
        """
        SELECT a.feedback.id AS feedbackId, a.id AS id, a.filename AS filename,
               a.contentType AS contentType, a.sizeBytes AS sizeBytes
        FROM FeedbackAttachmentEntity a
        WHERE a.feedback.id IN :feedbackIds
        """,
    )
    fun findAttachmentMetaByFeedbackIds(
        @Param("feedbackIds") feedbackIds: Collection<Long>,
    ): List<FeedbackAttachmentMetaView>

    /**
     * Retention prune: delete RESOLVED reports last touched before [cutoff]. Their
     * attachments go with them via the `ON DELETE CASCADE` FK. NEW/IN_PROGRESS rows
     * are never pruned regardless of age. Returns rows deleted.
     */
    @Modifying
    @Transactional
    @Query(
        "DELETE FROM FeedbackEntity f WHERE f.status = :status AND f.updatedAt IS NOT NULL AND f.updatedAt < :cutoff",
    )
    fun deleteResolvedUpdatedBefore(
        @Param("status") status: String,
        @Param("cutoff") cutoff: Instant,
    ): Int

    /** Count of "open" reports = anything not in [status] (RESOLVED), i.e. NEW + IN_PROGRESS. */
    fun countByStatusNot(status: String): Long
}

/**
 * Interface projection of a feedback attachment WITHOUT the `data` blob — used by the
 * list/detail metadata query so screenshot bytes are never loaded off the hot path.
 */
interface FeedbackAttachmentMetaView {
    val feedbackId: Long
    val id: Long
    val filename: String?
    val contentType: String?
    val sizeBytes: Int?
}
