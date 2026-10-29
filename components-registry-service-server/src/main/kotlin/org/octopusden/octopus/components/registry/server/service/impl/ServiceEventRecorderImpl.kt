package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.entity.ServiceEventEntity
import org.octopusden.octopus.components.registry.server.repository.ServiceEventRepository
import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Best-effort recorder. Each write goes through a `REQUIRES_NEW` [TransactionTemplate]
 * so it commits (or rolls back) independently of any surrounding transaction, wrapped
 * in a `try/catch` OUTSIDE the transaction so a failed/rolled-back journal write is
 * swallowed with a WARN rather than propagated into the job/boot that triggered it.
 *
 * Mirrors the `REQUIRES_NEW`-per-write precedent of
 * [org.octopusden.octopus.components.registry.server.service.impl.GitHistoryCommitWriter],
 * but keeps the fail-safe wrapper in the same class via an explicit template rather
 * than relying on self-invocation proxying.
 */
@ConditionalOnDatabaseEnabled
@Service
class ServiceEventRecorderImpl(
    private val repository: ServiceEventRepository,
    transactionManager: PlatformTransactionManager,
) : ServiceEventRecorder {
    private val requiresNew =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun recordStart(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        summary: String?,
    ) = safe("recordStart", type, correlationId) {
        requiresNew.executeWithoutResult {
            repository.save(
                ServiceEventEntity(
                    eventType = type.name,
                    status = ServiceEventStatus.RUNNING.name,
                    source = source.wire,
                    triggeredBy = triggeredBy,
                    correlationId = correlationId,
                    summary = summary,
                    startedAt = Instant.now(),
                ),
            )
        }
    }

    override fun recordFinish(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        status: ServiceEventStatus,
        summary: String?,
        detail: Map<String, Any?>?,
    ) = safe("recordFinish", type, correlationId) {
        requiresNew.executeWithoutResult {
            val running =
                repository.findFirstByCorrelationIdAndStatusOrderByIdDesc(
                    correlationId,
                    ServiceEventStatus.RUNNING.name,
                )
            val now = Instant.now()
            if (running != null) {
                running.status = status.name
                running.finishedAt = now
                summary?.let { running.summary = it }
                detail?.let { running.detail = it }
                repository.save(running)
            } else {
                // No RUNNING row to close (e.g. the executor rejected the submission
                // before the runnable ran, so recordStart never fired). Insert a
                // terminal row so the failure is still recorded (SYS-060).
                repository.save(
                    ServiceEventEntity(
                        eventType = type.name,
                        status = status.name,
                        source = source.wire,
                        triggeredBy = triggeredBy,
                        correlationId = correlationId,
                        summary = summary,
                        detail = detail,
                        startedAt = now,
                        finishedAt = now,
                    ),
                )
            }
        }
    }

    override fun recordInstant(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        status: ServiceEventStatus,
        serviceVersion: String?,
        correlationId: String?,
        summary: String?,
        detail: Map<String, Any?>?,
        startedAt: Instant,
        finishedAt: Instant?,
    ) = safe("recordInstant", type, correlationId) {
        requiresNew.executeWithoutResult {
            repository.save(
                ServiceEventEntity(
                    eventType = type.name,
                    status = status.name,
                    source = source.wire,
                    triggeredBy = triggeredBy,
                    serviceVersion = serviceVersion,
                    correlationId = correlationId,
                    summary = summary,
                    detail = detail,
                    startedAt = startedAt,
                    finishedAt = finishedAt ?: startedAt,
                ),
            )
        }
    }

    override fun reconcileOrphanedRunning(source: ServiceEventSource): Int =
        safeCount("reconcileOrphanedRunning") {
            requiresNew.execute {
                repository.reconcileRunning(
                    source = source.wire,
                    running = ServiceEventStatus.RUNNING.name,
                    failed = ServiceEventStatus.FAILED.name,
                    summary = INTERRUPTED_BY_RESTART,
                    now = Instant.now(),
                )
            } ?: 0
        }

    override fun prune(cutoff: Instant): Int =
        safeCount("prune") {
            requiresNew.execute { repository.deleteByStartedAtBefore(cutoff) } ?: 0
        }

    private inline fun safe(
        op: String,
        type: ServiceEventType,
        correlationId: String?,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.warn("service-event {} failed (type={}, correlationId={}) — journal write skipped", op, type, correlationId, e)
        }
    }

    private inline fun safeCount(
        op: String,
        block: () -> Int,
    ): Int =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.warn("service-event {} failed — skipped", op, e)
            0
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(ServiceEventRecorderImpl::class.java)
        const val INTERRUPTED_BY_RESTART = "interrupted by restart"
    }
}
