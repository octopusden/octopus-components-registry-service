package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * SYS-062 — a user feedback / "report a problem" submission. Free-form user content
 * with its own triage lifecycle (NEW/IN_PROGRESS/RESOLVED), distinct from the
 * entity-change trail ([AuditLogEntity]) and the operational journal
 * ([ServiceEventEntity]).
 *
 * `type`/`status` are stored as enum `name()` strings (CHECK-constrained in the DDL).
 * `detail` mirrors the JSON convention (plain `TEXT` column + `@JdbcTypeCode(JSON)`)
 * for diagnostic context (user-agent, …).
 *
 * [attachments] is LAZY and MUST NOT be traversed on the admin list path — each
 * attachment row carries a full screenshot `bytea`, so touching the collection there
 * would fetch every blob. List/detail views project attachment metadata via a
 * separate no-`data` query (see FeedbackRepository); the bytes are read only by the
 * dedicated attachment endpoint.
 */
@Entity
@Table(name = "feedback")
class FeedbackEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "type", nullable = false, length = 20)
    var type: String = "",

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "",

    @Column(name = "title", columnDefinition = "TEXT")
    var title: String? = null,

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    var message: String = "",

    @Column(name = "submitted_by", length = 255)
    var submittedBy: String? = null,

    @Column(name = "page_url", columnDefinition = "TEXT")
    var pageUrl: String? = null,

    @Column(name = "app_version", length = 100)
    var appVersion: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "TEXT")
    var detail: Map<String, Any?>? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @Column(name = "updated_by", length = 255)
    var updatedBy: String? = null,

    @OneToMany(
        mappedBy = "feedback",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    var attachments: MutableList<FeedbackAttachmentEntity> = mutableListOf(),
) {
    /** Add [attachment] and wire the back-reference so the cascade persists it. */
    fun addAttachment(attachment: FeedbackAttachmentEntity) {
        attachment.feedback = this
        attachments.add(attachment)
    }
}
