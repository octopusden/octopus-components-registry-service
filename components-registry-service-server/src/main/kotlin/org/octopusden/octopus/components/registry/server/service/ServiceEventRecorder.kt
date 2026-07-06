package org.octopusden.octopus.components.registry.server.service

import java.time.Instant

/**
 * SYS-060: records operational service events into the append-only `service_event`
 * journal. Every method is **best-effort** — a journal write must never roll back
 * or crash the job/boot it observes, so failures are caught and logged, not
 * propagated. Each write runs in its own `REQUIRES_NEW` transaction (the callers
 * run on background-executor / startup threads that carry no ambient transaction).
 *
 * Lifecycle contract for a job run:
 *  - [recordStart] once, at the top of the work runnable → a RUNNING row.
 *  - [recordFinish] once, at the terminal branch → transitions that RUNNING row
 *    in place to COMPLETED/FAILED. If no RUNNING row exists (e.g. the executor
 *    rejected the submission before the runnable ran), it inserts a terminal row
 *    instead, so a failure is never silently lost.
 *
 * [recordInstant] writes a single terminal row in one shot — used for STARTUP
 * markers and for portal-sourced events (validation sweeps, portal redeploys)
 * that arrive already-terminal over the ingest endpoint.
 */
interface ServiceEventRecorder {
    fun recordStart(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        summary: String?,
    )

    fun recordFinish(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        status: ServiceEventStatus,
        summary: String?,
        detail: Map<String, Any?>?,
    )

    @Suppress("LongParameterList") // A service-event is a flat record; each field is independent.
    fun recordInstant(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        status: ServiceEventStatus = ServiceEventStatus.COMPLETED,
        serviceVersion: String? = null,
        correlationId: String? = null,
        summary: String? = null,
        detail: Map<String, Any?>? = null,
        startedAt: Instant = Instant.now(),
        finishedAt: Instant? = null,
    )

    /**
     * Flip every still-RUNNING row of [source] to FAILED ("interrupted by restart").
     * Called once on startup; single-pod only. Returns rows reconciled.
     */
    fun reconcileOrphanedRunning(source: ServiceEventSource): Int

    /** Delete rows started before [cutoff]. Returns rows pruned. */
    fun prune(cutoff: Instant): Int
}

/**
 * Inert recorder. Used as the default constructor arg on the async job impls so unit
 * tests that don't care about journaling can construct them without a recorder; Spring
 * injects the real [ServiceEventRecorder] bean in production (a Kotlin-optional param is
 * still autowired when a matching bean exists). Also the safe no-op in the no-db profile,
 * where no recorder bean is registered.
 */
object NoOpServiceEventRecorder : ServiceEventRecorder {
    override fun recordStart(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        summary: String?,
    ) = Unit

    override fun recordFinish(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        status: ServiceEventStatus,
        summary: String?,
        detail: Map<String, Any?>?,
    ) = Unit

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
    ) = Unit

    override fun reconcileOrphanedRunning(source: ServiceEventSource): Int = 0

    override fun prune(cutoff: Instant): Int = 0
}
