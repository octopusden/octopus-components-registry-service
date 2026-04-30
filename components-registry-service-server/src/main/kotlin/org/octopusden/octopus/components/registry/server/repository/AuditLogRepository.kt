package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

interface AuditLogRepository :
    JpaRepository<AuditLogEntity, Long>,
    JpaSpecificationExecutor<AuditLogEntity> {
    fun findByEntityTypeAndEntityId(
        entityType: String,
        entityId: String,
        pageable: Pageable,
    ): Page<AuditLogEntity>

    fun findAllByOrderByChangedAtDesc(pageable: Pageable): Page<AuditLogEntity>

    fun findByChangedBy(
        changedBy: String,
        pageable: Pageable,
    ): Page<AuditLogEntity>

    @Modifying
    @Transactional
    fun deleteBySource(source: String): Long
}
