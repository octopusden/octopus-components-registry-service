package org.octopusden.octopus.components.registry.server.teamcity.sync

import org.octopusden.octopus.components.registry.server.service.JobState
import java.time.Instant

/**
 * Server-side state of a long-running `POST /admin/teamcity-project-ids/sync` run.
 *
 * Mirrors `MigrationJobState` for components; the only domain-specific payload is the
 * per-pass [result] (component counts) emitted on COMPLETED. No per-component progress
 * fields in v1.
 *
 * State lives in an [java.util.concurrent.atomic.AtomicReference] inside the impl —
 * single-pod scope. A pod restart drops it along with any in-flight sync; TC sync is
 * **not resumable**, so restarting from scratch is the right semantic.
 */
data class TeamcitySyncJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    /** Populated only on [JobState.COMPLETED]; carries the per-pass counters. */
    val result: TeamcitySyncResult?,
    val errorMessage: String?,
)

/** Outcome of [TeamcitySyncJobService.startAsync]. */
data class StartTeamcitySyncResult(
    /** Either the freshly-started job, or the already-running one. */
    val state: TeamcitySyncJobState,
    /** `true` if this call started a new job; `false` if a RUNNING job was already active. */
    val isNewlyStarted: Boolean,
)

/**
 * Async wrapper around [TeamcitySyncService.resync]. Mirrors
 * `MigrationJobService` for the components flow.
 *
 * The endpoint pair `POST /admin/teamcity-project-ids/sync` + `GET .../sync/job`
 * goes through this service so callers poll instead of holding an HTTP
 * connection while TC is being scanned.
 */
interface TeamcitySyncJobService {
    /**
     * If no resync is currently RUNNING, claim a slot, start the work on the background
     * executor, and return the freshly-created [TeamcitySyncJobState] with `isNewlyStarted=true`.
     *
     * If a same-kind RUNNING job is already active, return its state with `isNewlyStarted=false`
     * instead of spawning a duplicate — the controller maps that to HTTP 409 so the SPA can
     * attach to the in-flight job. If the gate is held by a different kind (components/history
     * migration), throws
     * [org.octopusden.octopus.components.registry.server.service.MigrationConflictException],
     * mapped to a structured cross-kind 409. COMPLETED/FAILED states don't block a new call.
     *
     * [triggeredBy] is the actor recorded on the service-event journal row (SYS-060): a username
     * for admin-triggered runs, `"scheduler"` for the cron. Captured on the caller thread since
     * the background executor doesn't carry the security context.
     */
    fun startAsync(triggeredBy: String = "system"): StartTeamcitySyncResult

    /** Latest known state, or `null` if no job has been started since the pod booted. */
    fun current(): TeamcitySyncJobState?
}
