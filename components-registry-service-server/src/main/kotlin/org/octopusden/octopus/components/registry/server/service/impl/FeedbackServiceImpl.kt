package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.config.FeedbackProperties
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackAttachmentMeta
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackCreateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackFilter
import org.octopusden.octopus.components.registry.server.dto.v4.FeedbackResponse
import org.octopusden.octopus.components.registry.server.entity.FeedbackAttachmentEntity
import org.octopusden.octopus.components.registry.server.entity.FeedbackEntity
import org.octopusden.octopus.components.registry.server.repository.FeedbackAttachmentMetaView
import org.octopusden.octopus.components.registry.server.repository.FeedbackAttachmentRepository
import org.octopusden.octopus.components.registry.server.repository.FeedbackRepository
import org.octopusden.octopus.components.registry.server.service.FeedbackAttachmentContent
import org.octopusden.octopus.components.registry.server.service.FeedbackService
import org.octopusden.octopus.components.registry.server.service.FeedbackStatus
import org.octopusden.octopus.components.registry.server.service.ImageMimeDetector
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale

@ConditionalOnDatabaseEnabled
@Service
class FeedbackServiceImpl(
    private val feedbackRepository: FeedbackRepository,
    private val attachmentRepository: FeedbackAttachmentRepository,
    private val properties: FeedbackProperties,
) : FeedbackService {
    @Transactional
    override fun submit(
        request: FeedbackCreateRequest,
        submittedBy: String,
        userAgent: String?,
    ): FeedbackResponse {
        val type = requireNotNull(request.type) { "type is required" }
        val attachments = request.attachments.orEmpty()
        require(attachments.size <= properties.maxAttachments) {
            "at most ${properties.maxAttachments} attachments are allowed"
        }

        val entity =
            FeedbackEntity(
                type = type.name,
                status = FeedbackStatus.NEW.name,
                title = request.title?.trim()?.takeIf { it.isNotEmpty() },
                message = request.message.trim(),
                submittedBy = submittedBy,
                pageUrl = request.pageUrl?.trim()?.takeIf { it.isNotEmpty() },
                appVersion = request.appVersion?.trim()?.takeIf { it.isNotEmpty() },
                detail = userAgent?.let { mapOf("userAgent" to it) },
                createdAt = Instant.now(),
            )
        attachments.forEach { entity.addAttachment(toAttachment(it.filename, it.dataBase64)) }

        val saved = feedbackRepository.save(entity)
        return FeedbackResponse.from(saved, savedAttachmentMeta(saved))
    }

    @Transactional(readOnly = true)
    override fun list(
        filter: FeedbackFilter,
        pageable: Pageable,
    ): Page<FeedbackResponse> {
        val page = feedbackRepository.findAll(buildSpecification(filter), withDefaultSort(pageable))
        val ids = page.content.mapNotNull { it.id }
        val metaByFeedback: Map<Long, List<FeedbackAttachmentMeta>> =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                feedbackRepository.findAttachmentMetaByFeedbackIds(ids)
                    .groupBy({ it.feedbackId }, { toMeta(it) })
            }
        return page.map { FeedbackResponse.from(it, metaByFeedback[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    override fun get(id: Long): FeedbackResponse {
        val entity = feedbackRepository.findById(id).orElseThrow { NotFoundException("Feedback $id not found") }
        val meta = feedbackRepository.findAttachmentMetaByFeedbackIds(listOf(id)).map { toMeta(it) }
        return FeedbackResponse.from(entity, meta)
    }

    @Transactional
    override fun updateStatus(
        id: Long,
        status: FeedbackStatus,
        updatedBy: String,
    ): FeedbackResponse {
        val entity = feedbackRepository.findById(id).orElseThrow { NotFoundException("Feedback $id not found") }
        entity.status = status.name
        entity.updatedAt = Instant.now()
        entity.updatedBy = updatedBy
        val saved = feedbackRepository.save(entity)
        val meta = feedbackRepository.findAttachmentMetaByFeedbackIds(listOf(id)).map { toMeta(it) }
        return FeedbackResponse.from(saved, meta)
    }

    @Transactional(readOnly = true)
    override fun getAttachment(
        feedbackId: Long,
        attachmentId: Long,
    ): FeedbackAttachmentContent? =
        attachmentRepository.findByIdAndFeedback_Id(attachmentId, feedbackId)?.let {
            FeedbackAttachmentContent(
                filename = it.filename,
                contentType = it.contentType ?: ImageMimeDetector.PNG,
                data = it.data,
            )
        }

    @Transactional
    override fun prune(): Int {
        if (properties.retentionDays <= 0) return 0
        val cutoff = Instant.now().minus(Duration.ofDays(properties.retentionDays))
        return feedbackRepository.deleteResolvedUpdatedBefore(FeedbackStatus.RESOLVED.name, cutoff)
    }

    /**
     * Decode base64 → validate size → derive the REAL MIME from magic bytes (never the
     * client's claim). Rejects an over-cap or non-PNG/JPEG payload with a 400
     * (IllegalArgumentException). [filename] is sanitized to a safe basename.
     */
    private fun toAttachment(
        filename: String?,
        dataBase64: String,
    ): FeedbackAttachmentEntity {
        val bytes =
            try {
                Base64.getDecoder().decode(stripDataUrlPrefix(dataBase64).trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("attachment is not valid base64", e)
            }
        require(bytes.isNotEmpty()) { "attachment is empty" }
        require(bytes.size <= properties.maxAttachmentBytes) {
            "attachment exceeds ${properties.maxAttachmentBytes} bytes"
        }
        val mime =
            ImageMimeDetector.detect(bytes)
                ?: throw IllegalArgumentException("attachment must be a PNG or JPEG image")
        return FeedbackAttachmentEntity(
            filename = sanitizeFilename(filename),
            contentType = mime,
            sizeBytes = bytes.size,
            data = bytes,
        )
    }

    private fun savedAttachmentMeta(entity: FeedbackEntity): List<FeedbackAttachmentMeta> =
        entity.attachments.map {
            FeedbackAttachmentMeta(
                id = requireNotNull(it.id) { "persisted attachment has a null id" },
                filename = it.filename,
                contentType = it.contentType,
                sizeBytes = it.sizeBytes,
            )
        }

    private fun toMeta(view: FeedbackAttachmentMetaView): FeedbackAttachmentMeta =
        FeedbackAttachmentMeta(
            id = view.id,
            filename = view.filename,
            contentType = view.contentType,
            sizeBytes = view.sizeBytes,
        )

    private fun withDefaultSort(pageable: Pageable): Pageable =
        if (pageable.sort.isUnsorted) {
            PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        } else {
            pageable
        }

    private fun buildSpecification(filter: FeedbackFilter): Specification<FeedbackEntity> {
        var spec = Specification.where<FeedbackEntity>(null)
        // Stored values are canonical enum name()s → normalize the INPUT and compare to
        // the column directly (keeps the btree indexes on type/status usable).
        filter.type?.takeIf { it.isNotBlank() }?.let { type ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("type"), type.uppercase(Locale.ROOT)) })
        }
        filter.status?.takeIf { it.isNotBlank() }?.let { status ->
            spec = spec.and(Specification { root, _, cb -> cb.equal(root.get<String>("status"), status.uppercase(Locale.ROOT)) })
        }
        return spec
    }

    private fun stripDataUrlPrefix(value: String): String {
        val marker = "base64,"
        val idx = value.indexOf(marker)
        return if (value.startsWith("data:") && idx >= 0) value.substring(idx + marker.length) else value
    }

    /** Keep only the basename and a conservative charset; drop path separators and control chars. */
    private fun sanitizeFilename(filename: String?): String? {
        val base = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()
        return base
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(MAX_FILENAME)
            ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val MAX_FILENAME = 120
    }
}
