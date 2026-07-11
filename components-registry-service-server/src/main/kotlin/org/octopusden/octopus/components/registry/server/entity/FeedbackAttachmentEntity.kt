package org.octopusden.octopus.components.registry.server.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * SYS-062 — one screenshot attached to a [FeedbackEntity], stored inline as `bytea`.
 * [contentType] is the SERVER-normalized MIME (derived from magic bytes at submit
 * time, never the client's claim) and is echoed on the attachment-bytes response.
 *
 * [data] is a full image blob; it is fetched ONLY through the dedicated attachment
 * endpoint. List/detail views use the metadata projection in FeedbackRepository,
 * which never selects this column.
 */
@Entity
@Table(name = "feedback_attachment")
class FeedbackAttachmentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feedback_id", nullable = false)
    var feedback: FeedbackEntity? = null,

    @Column(name = "filename", columnDefinition = "TEXT")
    var filename: String? = null,

    @Column(name = "content_type", length = 100)
    var contentType: String? = null,

    @Column(name = "size_bytes")
    var sizeBytes: Int? = null,

    @Column(name = "data", nullable = false)
    var data: ByteArray = ByteArray(0),
)
