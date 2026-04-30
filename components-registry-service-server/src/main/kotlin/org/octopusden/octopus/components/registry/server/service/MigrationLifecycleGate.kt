package org.octopusden.octopus.components.registry.server.service

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-job concurrency gate shared by [MigrationJobService] (components) and
 * [HistoryMigrationJobService] — only one of the two may be RUNNING at a time.
 *
 * Why this exists separately from each service's own AtomicReference: each job
 * service has its own slot for the response shape ("what is the current
 * components job?") but neither knows about the other. Without a shared gate
 * both would happily start in parallel — the SPA would render two RUNNING
 * jobs, both backed by importers writing to the same DB tables. The
 * single-thread `migrationExecutor` doesn't help: ThreadPoolTaskExecutor with
 * `queueCapacity > 0` queues the second submit instead of rejecting it, so
 * both `startAsync()` calls return 202 with RUNNING and the second job just
 * starts later.
 *
 * Single-pod scope. If CRS is ever deployed with `replicas > 1` (or rolling
 * update with overlap), each pod owns its own gate and concurrent migrations
 * become possible again. That is also true of the existing components-only
 * gate in [MigrationJobServiceImpl]; making it DB-backed is a followup.
 */
@Component
class MigrationLifecycleGate {
    enum class JobKind { COMPONENTS, HISTORY }

    data class ActiveJob(
        val kind: JobKind,
        val jobId: String,
    )

    private val active = AtomicReference<ActiveJob?>(null)

    /**
     * CAS-loop claim. Returns null when this caller now owns the gate; returns
     * the existing [ActiveJob] (any kind) when something else owns it.
     *
     * Loop matters: a naive `compareAndSet(null, candidate); return active.get()`
     * has a race where the holder release()s between the failed CAS and the
     * subsequent get(), so the second caller sees null and thinks it claimed
     * the gate without ever installing itself. A third caller can then claim
     * the now-null slot, and both proceed believing they hold the gate. The
     * loop guarantees we either observe a live owner (returned as conflict) or
     * win our own CAS on a confirmed-null slot.
     */
    fun tryClaim(
        kind: JobKind,
        jobId: String,
    ): ActiveJob? {
        val candidate = ActiveJob(kind, jobId)
        while (true) {
            val current = active.get()
            if (current != null) return current
            if (active.compareAndSet(null, candidate)) return null
        }
    }

    /**
     * Release the slot iff [jobId] is the current owner. Idempotent — safe to
     * call from multiple finally blocks; safe to call after a stale jobId has
     * been replaced by a newer claim.
     */
    fun release(jobId: String) {
        active.updateAndGet { current -> if (current?.jobId == jobId) null else current }
    }

    /** Snapshot of the active claim, or null if the gate is free. */
    fun current(): ActiveJob? = active.get()
}
