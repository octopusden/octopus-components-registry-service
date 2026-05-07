package org.octopusden.octopus.components.registry.server.teamcity

import org.octopusden.octopus.components.registry.server.service.JobState
import java.time.Instant

/**
 * Server-side state of a long-running `POST /admin/teamcity-project-ids/sync` run.
 *
 * Mirrors `MigrationJobState` for components but with TC-specific shape: the only
 * domain-specific payload is the per-pass [result] (component counts) emitted on
 * COMPLETED. There are no per-component progress fields in v1 — adding them
 * would require threading a `TeamcitySyncProgressListener` through
 * `TcProjectFetcher`; the JSON shape leaves room (the SPA can flip a spinner
 * to a counter view without a backend contract change).
 *
 * State lives in an [java.util.concurrent.atomic.AtomicReference] inside the
 * impl — single-pod scope. A pod restart drops it (and the in-flight sync along
 * with it); the operator sees "no current job" and starts fresh. TC sync is
 * **not resumable** — restarting from scratch is the right semantic.
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
     * If no resync is currently RUNNING, claim a slot, start the work on the
     * background executor, and return the freshly-created [TeamcitySyncJobState]
     * with `isNewlyStarted=true`.
     *
     * If a same-kind RUNNING job is already active, return the existing state
     * with `isNewlyStarted=false` and do NOT spawn a duplicate run. The
     * controller maps `isNewlyStarted=false` to HTTP 409 so the SPA can
     * "attach" to the in-flight job rather than start a parallel one.
     *
     * If the gate is held by a different kind (components migration, history
     * migration), throws
     * [org.octopusden.octopus.components.registry.server.service.MigrationConflictException]
     * which the controller maps to HTTP 409 with a structured cross-kind body.
     *
     * COMPLETED / FAILED states do not block: a new call after a finished job
     * replaces the slot.
     */
    fun startAsync(): StartTeamcitySyncResult

    /** Latest known state, or `null` if no job has been started since the pod booted. */
    fun current(): TeamcitySyncJobState?
}
