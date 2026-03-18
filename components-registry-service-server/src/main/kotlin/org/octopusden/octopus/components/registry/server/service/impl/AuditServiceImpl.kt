package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.octopusden.octopus.components.registry.server.mapper.toResponse
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.service.AuditService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuditServiceImpl(
    private val auditLogRepository: AuditLogRepository,
) : AuditService {
    override fun getEntityHistory(
        entityType: String,
        entityId: String,
        pageable: Pageable,
    ): Page<AuditLogResponse> =
        auditLogRepository
            .findByEntityTypeAndEntityId(entityType, entityId, pageable)
            .map { it.toResponse() }

    override fun getRecentChanges(pageable: Pageable): Page<AuditLogResponse> =
        auditLogRepository
            .findAllByOrderByChangedAtDesc(pageable)
            .map { it.toResponse() }
}
