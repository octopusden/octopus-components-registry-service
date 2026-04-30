package org.octopusden.octopus.components.registry.server.service

import java.time.Instant

/**
 * Server-side state of a long-running POST /rest/api/4/admin/migrate run.
 *
 * The migration is fundamentally async: a ~1k-component run takes longer
 * than the portal gateway's HTTP read timeout, so the synchronous POST
 * returns 504 even though ImportService keeps working. MigrationJobService
 * spawns the work on a single-thread executor and exposes per-component
 * counters + the current component name through this state record so the
 * SPA can render real progress.
 *
 * State lives in an [AtomicReference] inside the impl — single-pod scope.
 * A pod restart drops it (and the migration along with it); the operator
 * sees "no current job" and starts fresh. If we later need cross-pod
 * visibility or restart resilience, the GitHistoryImportStateEntity
 * pattern (PR #151, table-backed claim via `INSERT ... ON CONFLICT DO
 * NOTHING`) is the next step.
 */
data class MigrationJobState(
    val id: String,
    val state: JobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val total: Int,
    val migrated: Int,
    val failed: Int,
    val skipped: Int,
    val currentComponent: String?,
    val errorMessage: String?,
    val result: FullMigrationResult?,
    /**
     * Sub-phase the migration is currently in. Lets the SPA render an
     * informative label ("Loading defaults from Git…", "Resolving components
     * from Git…") during the early window before the per-component progress
     * listener has fired its first event — that window can take many seconds
     * on a cold git clone, and a frozen "Running…" with `total=0` was the
     * original UX complaint that motivated this field.
     *
     * `null` only on terminal states (COMPLETED / FAILED) where no phase is
     * active. While RUNNING this is always set.
     */
    val phase: MigrationPhase? = null,
)

enum class JobState { RUNNING, COMPLETED, FAILED }

/**
 * Phases of `ImportService.migrate(progress)` exposed to the SPA so it can
 * render meaningful text while progress counters are still zero. Closed enum
 * — DEFAULTS happens before any per-component event, COMPONENTS while the
 * loop is iterating. There is no DONE / COMPLETED phase: terminal job state
 * lives in [JobState], and `phase` is cleared to null on transition.
 */
enum class MigrationPhase { DEFAULTS, COMPONENTS }

/** Outcome of [MigrationJobService.startAsync]. */
data class StartMigrationResult(
    /** Either the freshly-started job, or the already-running one. */
    val state: MigrationJobState,
    /** `true` if this call started a new job; `false` if a RUNNING job was already active. */
    val isNewlyStarted: Boolean,
)

interface MigrationJobService {
    /**
     * If no migration is currently RUNNING, claim a slot, start the work on the background
     * executor, and return the freshly-created [MigrationJobState] with `isNewlyStarted=true`.
     *
     * If a RUNNING job is already active, return the existing state with
     * `isNewlyStarted=false` and do NOT spawn a duplicate run. This is the idempotency
     * gate — the controller maps `isNewlyStarted=false` to HTTP 409 so the SPA can
     * "attach" to the in-flight job rather than start a parallel one.
     *
     * COMPLETED / FAILED states do not block: a new call after a finished job replaces
     * the slot.
     */
    fun startAsync(): StartMigrationResult

    /** Latest known job state, or `null` if no job has been started since the pod booted. */
    fun current(): MigrationJobState?
}

/**
 * Progress sink that [ImportService.migrate] can drive while it walks the component
 * list. The default [NOOP] implementation lets existing callers (tests, the legacy
 * sync `/migrate-components` endpoint) keep working without progress reporting.
 */
fun interface MigrationProgressListener {
    fun onProgress(event: MigrationProgressEvent)

    companion object {
        val NOOP: MigrationProgressListener = MigrationProgressListener { }
    }
}

data class MigrationProgressEvent(
    val componentName: String,
    val migrated: Int,
    val failed: Int,
    val skipped: Int,
    val total: Int,
)

/**
 * Thrown by either job service's startAsync when [MigrationLifecycleGate] reports
 * a *cross-kind* conflict (this caller is COMPONENTS, but HISTORY holds the gate,
 * or vice versa). Same-kind conflicts are NOT exceptions — they resolve to the
 * existing [StartMigrationResult] with `isNewlyStarted=false` so the caller can
 * attach to the in-flight job. Cross-kind has no equivalent attach path: the
 * caller wanted a different operation and there is nothing useful to attach to.
 *
 * The controller layer maps this to HTTP 409 with a structured error body
 * carrying [activeKind] / [activeJobId] so the SPA can render a clear message
 * ("History migration is currently running. Wait for it to finish.").
 */
class MigrationConflictException(
    val active: MigrationLifecycleGate.ActiveJob,
) : RuntimeException("Cross-kind migration conflict: ${active.kind} job ${active.jobId} is already running")
