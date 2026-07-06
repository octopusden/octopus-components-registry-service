package org.octopusden.octopus.components.registry.server.repository

import org.octopusden.octopus.components.registry.server.entity.ServiceEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface ServiceEventRepository :
    JpaRepository<ServiceEventEntity, Long>,
    JpaSpecificationExecutor<ServiceEventEntity> {
    /**
     * The RUNNING row for a job id, if any. Used by `recordFinish` to transition a
     * run in place; matching on status (not just correlationId) means a
     * finish never overwrites an already-terminal row, and the caller falls back
     * to inserting a terminal row when this returns null (SYS-060).
     */
    fun findFirstByCorrelationIdAndStatusOrderByIdDesc(
        correlationId: String,
        status: String,
    ): ServiceEventEntity?

    /**
     * Reconcile runs interrupted by a pod restart: flip every still-RUNNING row of
     * a given source to FAILED with a fixed reason + finish time. Single-pod only
     * (prod replicas=1) — flipping a peer pod's live run would be wrong; see
     * `ServiceStartupListener`. Returns the number of rows reconciled.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE ServiceEventEntity e
        SET e.status = :failed, e.summary = :summary, e.finishedAt = :now
        WHERE e.source = :source AND e.status = :running
        """,
    )
    fun reconcileRunning(
        @Param("source") source: String,
        @Param("running") running: String,
        @Param("failed") failed: String,
        @Param("summary") summary: String,
        @Param("now") now: Instant,
    ): Int

    /** Retention prune: delete terminal rows older than the cutoff. Returns rows deleted. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ServiceEventEntity e WHERE e.startedAt < :cutoff")
    fun deleteByStartedAtBefore(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
