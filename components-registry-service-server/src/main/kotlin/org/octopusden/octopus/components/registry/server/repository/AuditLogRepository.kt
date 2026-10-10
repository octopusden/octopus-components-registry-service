package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface AuditLogRepository :
    JpaRepository<AuditLogEntity, Long>,
    JpaSpecificationExecutor<AuditLogEntity> {
    fun findByEntityTypeAndEntityId(
        entityType: String,
        entityId: String,
        pageable: Pageable,
    ): Page<AuditLogEntity>

    /**
     * Entity history with git-history baseline rows hidden — `action != :action`
     * (where `:action` is `MIGRATED`). Backs the default (`includeMigrated=false`)
     * path of `getEntityHistory`; the unfiltered method above serves the opt-in
     * path. SYS-049.
     */
    fun findByEntityTypeAndEntityIdAndActionNot(
        entityType: String,
        entityId: String,
        action: String,
        pageable: Pageable,
    ): Page<AuditLogEntity>

    fun findByChangedBy(
        changedBy: String,
        pageable: Pageable,
    ): Page<AuditLogEntity>

    fun findAllByOrderByChangedAtDesc(pageable: Pageable): Page<AuditLogEntity>

    /**
     * ONB-002 (TeamCity placement Sync): every real edit of a component, newest first, used by
     * `PlacementEditHistory` to find the LAST audited change to a placement field and decide
     * whether it was a real (manual) user write or the Sync job's own tagged write. `action IN
     * (UPDATE, RENAME)` excludes CREATE (nothing to compare a fresh component against) and MIGRATED
     * (git-history baseline noise, SYS-049).
     */
    fun findByEntityTypeAndEntityIdAndActionInOrderByChangedAtDesc(
        entityType: String,
        entityId: String,
        actions: Collection<String>,
    ): List<AuditLogEntity>

    @Modifying
    @Transactional
    fun deleteBySource(source: String): Int

    @Query(
        """
        SELECT COALESCE(MAX(a.id), 0) AS maxId, COUNT(a) AS count
        FROM AuditLogEntity a
        WHERE a.source <> 'git-history'
        """,
    )
    fun changeStats(): AuditChangeStats
}

interface AuditChangeStats {
    val maxId: Long
    val count: Long
}
