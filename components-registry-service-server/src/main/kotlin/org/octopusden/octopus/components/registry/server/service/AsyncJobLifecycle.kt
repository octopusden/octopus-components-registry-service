package org.octopusden.octopus.components.registry.server.service

import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Composition-based async-job lifecycle helper extracted from
 * [org.octopusden.octopus.components.registry.server.service.impl.MigrationJobServiceImpl]
 * and [org.octopusden.octopus.components.registry.server.service.impl.HistoryMigrationJobServiceImpl].
 * Owns:
 *  - the in-memory state slot ([AtomicReference]);
 *  - the CAS+gate+publish claim ordering;
 *  - [TaskExecutor.execute] submit with rollback on rejection;
 *  - the id-guarded [update] helper for transitions inside the running work;
 *  - cross-kind serialization through [MigrationLifecycleGate].
 *
 * Composition over inheritance: each impl is a Spring `@Service` with its own
 * domain-specific dependencies, and we don't want every constructor to drag a
 * super-class with `executor` / `gate` arguments through the DI graph. With
 * composition each impl simply owns a `private val lifecycle = AsyncJobLifecycle<TState>(...)`
 * and delegates the boilerplate.
 *
 * Single-pod scope, mirroring the underlying [MigrationLifecycleGate]: this is
 * an in-memory slot, so a pod restart drops it. Operations that need cross-pod
 * resilience (e.g. history migration's table-backed `git_history_import_state`)
 * layer their own DB persistence on top of this helper rather than inside it.
 */
class AsyncJobLifecycle<TState : Any>(
    private val jobKind: MigrationLifecycleGate.JobKind,
    private val executor: TaskExecutor,
    private val gate: MigrationLifecycleGate,
    private val getId: (TState) -> String,
    private val isRunning: (TState) -> Boolean,
    /**
     * Build the FAILED-state replacement when the executor rejects the submission
     * (typical case: [java.util.concurrent.RejectedExecutionException] during pod
     * shutdown). Called inside the rollback path with the **current slot value
     * whose id equals jobId** — typically the freshly-published candidate, but
     * may be a successor produced by a progress update if one slipped in before
     * the rejection handler observed the slot. Either way the impl returns a
     * `state.copy(...)` flipped to FAILED with the appropriate error message
     * and any kind-specific clears (phase=null, recoveryAction=RETRY, etc.).
     */
    private val markRejected: (current: TState, rejection: Throwable) -> TState,
) {
    private val state = AtomicReference<TState?>()

    /** Latest known state in this pod, or `null` if no job has been claimed since boot. */
    fun current(): TState? = state.get()

    /**
     * Direct slot reset. Used by callers that maintain their own RUNNING-guard
     * (e.g. `HistoryMigrationJobService.clearInMemory` / `forceReset`) and are
     * willing to take responsibility for not stomping on a live job.
     */
    fun setIdle() {
        state.set(null)
    }

    /** Outcome of [claimAndSubmit]. Cross-kind conflicts are signalled via [MigrationConflictException]. */
    sealed interface ClaimOutcome<TState : Any> {
        /** A same-kind RUNNING state was already present; caller "attaches" rather than spawning a duplicate. */
        data class Attached<T : Any>(val state: T) : ClaimOutcome<T>

        /** Slot was claimed for a freshly-built candidate and the work has been submitted to the executor. */
        data class Started<T : Any>(val state: T) : ClaimOutcome<T>
    }

    /**
     * Atomic CAS-retry claim + submit.
     *
     * Order of operations is load-bearing:
     *  1. Read the slot. If a RUNNING state is present → return [ClaimOutcome.Attached].
     *  2. Mint a fresh `jobId` and ask [buildCandidate] for a candidate keyed off it.
     *  3. Try to claim the cross-kind gate.
     *      - cross-kind held → throw [MigrationConflictException] (controllers map to HTTP 409).
     *      - same-kind held but no RUNNING state visible to us → tiny race window between
     *        a peer's gate-claim and its state publication; retry the loop.
     *      - free → we own the gate; proceed.
     *  4. Publish the candidate via [AtomicReference.compareAndSet]. If the CAS races
     *     and loses (defensive — should be unreachable while we hold the gate),
     *     release the gate and retry.
     *  5. Hand the runnable to the executor as `executor.execute { work(jobId) }`.
     *     A `SyncTaskExecutor` (used in tests) runs the work to completion before
     *     this call returns; a real async executor returns immediately while the
     *     state is still RUNNING. Either way we read `state.get()` once more so
     *     the returned state is authoritative.
     *  6. If the executor refuses the submission (typically pod shutdown), the slot
     *     is rolled back: gate released, state flipped to FAILED via [markRejected],
     *     and the rejection is rethrown so the caller can surface the error.
     */
    fun claimAndSubmit(
        buildCandidate: (jobId: String) -> TState,
        work: (jobId: String) -> Unit,
    ): ClaimOutcome<TState> {
        while (true) {
            val existing = state.get()
            if (existing != null && isRunning(existing)) {
                return ClaimOutcome.Attached(existing)
            }

            val jobId = UUID.randomUUID().toString()
            val candidate = buildCandidate(jobId)

            val gateConflict = gate.tryClaim(jobKind, jobId)
            if (gateConflict != null) {
                if (gateConflict.kind != jobKind) {
                    throw MigrationConflictException(gateConflict)
                }
                // Same-kind gate is held by a peer that has claimed the gate but
                // not yet published its state. Retry — the next iteration will
                // see RUNNING and return Attached.
                continue
            }

            if (!state.compareAndSet(existing, candidate)) {
                // Defensive: with the gate held no other startAsync can overwrite
                // the slot, so this branch should be unreachable in practice.
                gate.release(jobId)
                continue
            }

            LOG.info("Starting {} job {}", jobKind, jobId)
            @Suppress("TooGenericExceptionCaught") // Throwable so executor rejection ALWAYS unwinds the slot.
            try {
                executor.execute { work(jobId) }
            } catch (rejected: Throwable) {
                LOG.error("Failed to submit {} job {} to executor", jobKind, jobId, rejected)
                gate.release(jobId)
                state.updateAndGet { current ->
                    if (current == null || getId(current) != jobId) current else markRejected(current, rejected)
                }
                throw rejected
            }

            return ClaimOutcome.Started(state.get() ?: candidate)
        }
    }

    /**
     * id-guarded transition. The [transform] runs only if the slot still carries
     * a state whose id equals [jobId] — otherwise this is a no-op. Used by the
     * running work to publish progress / completion / failure transitions
     * without ever overwriting a freshly-claimed successor.
     *
     * Returns the post-update state (or the un-touched current state if the guard
     * tripped); generally the caller can ignore the return value.
     */
    fun update(jobId: String, transform: (TState) -> TState): TState? =
        state.updateAndGet { current ->
            if (current == null || getId(current) != jobId) current else transform(current)
        }

    /** Release the cross-kind gate. Idempotent: safe to call from finally on a rejection-rolled-back jobId. */
    fun release(jobId: String) {
        gate.release(jobId)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(AsyncJobLifecycle::class.java)
    }
}
