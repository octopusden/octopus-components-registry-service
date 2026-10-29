package org.octopusden.octopus.components.registry.server.support

import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import java.time.Instant

/**
 * Hand-written capturing test double for [ServiceEventRecorder]. Preferred over a
 * Mockito mock here because the recorder's methods take non-null Kotlin enum args,
 * which Mockito's `any()` (returns null) trips before the proxy intercepts. Captures
 * each call so tests can assert what was recorded and in what order, without matchers.
 */
class RecordingServiceEventRecorder(
    private val reconcileResult: Int = 0,
    private val pruneResult: Int = 0,
) : ServiceEventRecorder {
    data class StartCall(
        val type: ServiceEventType,
        val source: ServiceEventSource,
        val triggeredBy: String?,
        val correlationId: String,
        val summary: String?,
    )

    data class FinishCall(
        val type: ServiceEventType,
        val source: ServiceEventSource,
        val triggeredBy: String?,
        val correlationId: String,
        val status: ServiceEventStatus,
        val summary: String?,
        val detail: Map<String, Any?>?,
    )

    data class InstantCall(
        val type: ServiceEventType,
        val source: ServiceEventSource,
        val triggeredBy: String?,
        val status: ServiceEventStatus,
        val serviceVersion: String?,
        val correlationId: String?,
        val summary: String?,
        val detail: Map<String, Any?>?,
        val startedAt: Instant,
        val finishedAt: Instant?,
    )

    val starts = mutableListOf<StartCall>()
    val finishes = mutableListOf<FinishCall>()
    val instants = mutableListOf<InstantCall>()
    val reconciledSources = mutableListOf<ServiceEventSource>()
    val prunedCutoffs = mutableListOf<Instant>()

    /** Method-name log for ordering assertions (e.g. reconcile before startup). */
    val order = mutableListOf<String>()

    override fun recordStart(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        summary: String?,
    ) {
        starts += StartCall(type, source, triggeredBy, correlationId, summary)
        order += "start"
    }

    override fun recordFinish(
        type: ServiceEventType,
        source: ServiceEventSource,
        triggeredBy: String?,
        correlationId: String,
        status: ServiceEventStatus,
        summary: String?,
        detail: Map<String, Any?>?,
    ) {
        finishes += FinishCall(type, source, triggeredBy, correlationId, status, summary, detail)
        order += "finish:$status"
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
    ) {
        instants += InstantCall(type, source, triggeredBy, status, serviceVersion, correlationId, summary, detail, startedAt, finishedAt)
        order += "instant:$status"
    }

    override fun reconcileOrphanedRunning(source: ServiceEventSource): Int {
        reconciledSources += source
        order += "reconcile"
        return reconcileResult
    }

    override fun prune(cutoff: Instant): Int {
        prunedCutoffs += cutoff
        order += "prune"
        return pruneResult
    }
}
