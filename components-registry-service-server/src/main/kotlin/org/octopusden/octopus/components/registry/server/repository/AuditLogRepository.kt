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

    @Modifying
    @Transactional
    fun deleteBySource(source: String): Int

    /**
     * Aggregates over `audit_log` for the `configRevision` cache-actuality token
     * (OCTOPUS-2472), excluding the one-shot git-history backfill baseline.
     *
     * Returns BOTH aggregates on purpose — each catches a case the other misses:
     *  - `count` catches a transaction that commits late with a *lower* id (the
     *    INSERT-gets-id / BEFORE_COMMIT-becomes-visible ordering gap `maxId` alone
     *    would skip).
     *  - `maxId` catches a delete-then-reinsert pair, which leaves `count` unchanged.
     *
     * `COALESCE(MAX(a.id), 0)` yields `(0, 0)` on an empty table rather than a null
     * max. The `<> 'git-history'` filter is a deny-list on purpose: a `source` value
     * introduced later is included by default, so the token fails toward invalidating
     * too often — the safe direction.
     */
    @Query(
        """
        SELECT COALESCE(MAX(a.id), 0) AS maxId, COUNT(a) AS count
        FROM AuditLogEntity a
        WHERE a.source <> 'git-history'
        """,
    )
    fun changeStats(): AuditChangeStats
}

/**
 * Projection for [AuditLogRepository.changeStats]: the `(maxId, count)` pair that
 * composes the audit half of the `configRevision` token.
 */
interface AuditChangeStats {
    val maxId: Long
    val count: Long
}
