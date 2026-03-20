package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface AuditService {
    fun getEntityHistory(
        entityType: String,
        entityId: String,
        pageable: Pageable,
    ): Page<AuditLogResponse>

    fun getRecentChanges(pageable: Pageable): Page<AuditLogResponse>
}
