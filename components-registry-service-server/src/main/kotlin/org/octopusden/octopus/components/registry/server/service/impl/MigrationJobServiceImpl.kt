package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationJobState
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind
import org.octopusden.octopus.components.registry.server.service.MigrationPhase
import org.octopusden.octopus.components.registry.server.service.MigrationProgressListener
import org.octopusden.octopus.components.registry.server.service.StartMigrationResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory async wrapper around [ImportService.migrate].
 *
 * Why in-memory and not table-backed: a single CRS pod owns the migration; a hard
 * restart kills the run regardless of where state lives. The
 * GitHistoryImportStateEntity pattern (PR #151) earns its keep when the operation
 * is genuinely resumable, which `ImportService.migrate` is not — it would re-do
 * every component on restart. So we trade resilience for code we can read in one
 * sitting.
 *
 * Concurrency model:
 *  - Same-kind 409 (a second components POST while one is RUNNING) is decided by
 *    reading the local AtomicReference. The SPA "attaches" to the in-flight job
 *    rather than spawning a duplicate.
 *  - Cross-kind serialization (a components POST while history is RUNNING) is
 *    decided by [MigrationLifecycleGate] — a shared bean both this service and
 *    HistoryMigrationJobService consult before claiming. ThreadPoolTaskExecutor
 *    on its own does NOT serialize cross-service: it queues the second submit,
 *    so without the gate both `startAsync` calls would return 202 and the SPA
 *    would render two RUNNING jobs.
 */
@Service
class MigrationJobServiceImpl(
    private val importService: ImportService,
    @Qualifier("migrationExecutor") private val executor: TaskExecutor,
    private val lifecycleGate: MigrationLifecycleGate,
) : MigrationJobService {
    private val state = AtomicReference<MigrationJobState?>()

    /**
     * Outcome of a single CAS-retry iteration in [startAsync]. Replaces what
     * was previously a multi-jump loop body with explicit named cases — each
     * one maps to exactly one terminal action in the loop driver.
     */
    private sealed interface ClaimAttempt {
        /** Same-kind start while one is RUNNING — caller attaches to existing. */
        data class Attached(
            val state: MigrationJobState,
        ) : ClaimAttempt

        /** Cross-kind gate held by HISTORY — caller throws to the controller's 409 mapper. */
        data class CrossKindConflict(
            val active: MigrationLifecycleGate.ActiveJob,
        ) : ClaimAttempt

        /** Lost a race (gate or state CAS); caller loops back for another attempt. */
        data object Retry : ClaimAttempt

        /** Won the slot; caller submits the runnable to the executor. */
        data class Claimed(
            val candidate: MigrationJobState,
        ) : ClaimAttempt
    }

    override fun startAsync(): StartMigrationResult {
        // Order of operations is load-bearing — see [tryClaim]. We loop only on
        // [ClaimAttempt.Retry]; every other case is a terminal action.
        while (true) {
            when (val attempt = tryClaim()) {
                is ClaimAttempt.Attached -> return StartMigrationResult(attempt.state, isNewlyStarted = false)
                is ClaimAttempt.CrossKindConflict -> throw MigrationConflictException(attempt.active)
                is ClaimAttempt.Claimed -> {
                    submitToExecutor(attempt.candidate)
                    // SyncTaskExecutor (used in tests) has already finished the run by now → callers see COMPLETED.
                    // Production async executor → RUNNING. Either way, return the authoritative current state.
                    return StartMigrationResult(state.get() ?: attempt.candidate, isNewlyStarted = true)
                }
                ClaimAttempt.Retry -> Unit // loop again
            }
        }
    }

    /**
     * One CAS-retry iteration. Decomposes the original loop body into:
     *   1. same-kind 409 from local AtomicReference (state is non-null when RUNNING),
     *   2. cross-kind gate claim (after the same-kind path so we never read
     *      nullable state inside the gate-conflict branch),
     *   3. publish state via CAS.
     *
     * A naive "gate first" version had a window where thread A claimed the gate but
     * hadn't yet written state, and thread B's same-kind 409 path would NPE on
     * `state.get()!!`. Same-kind first dodges that.
     */
    private fun tryClaim(): ClaimAttempt {
        val existing = state.get()
        if (existing?.state == JobState.RUNNING) {
            return ClaimAttempt.Attached(existing)
        }

        val candidate =
            MigrationJobState(
                id = UUID.randomUUID().toString(),
                state = JobState.RUNNING,
                startedAt = Instant.now(),
                finishedAt = null,
                total = 0,
                migrated = 0,
                failed = 0,
                skipped = 0,
                currentComponent = null,
                errorMessage = null,
                result = null,
                // CRITICAL: phase is set on the candidate BEFORE executor.execute,
                // not inside the runnable. ThreadPoolTaskExecutor.execute returns
                // synchronously and the controller builds its response from
                // state.get() right after — if phase weren't already DEFAULTS the
                // first SPA poll tick would see RUNNING with no phase and render
                // the fallback "Running…" instead of "Loading defaults from Git…".
                phase = MigrationPhase.DEFAULTS,
            )

        val gateConflict = lifecycleGate.tryClaim(JobKind.COMPONENTS, candidate.id)
        if (gateConflict != null) {
            return when (gateConflict.kind) {
                JobKind.COMPONENTS -> ClaimAttempt.Retry
                JobKind.HISTORY -> ClaimAttempt.CrossKindConflict(gateConflict)
            }
        }

        // Gate is ours. Publish state. With the gate held no other startAsync of
        // either kind is in this critical section, so the CAS should always succeed
        // — the false branch is defensive belt-and-braces.
        if (!state.compareAndSet(existing, candidate)) {
            lifecycleGate.release(candidate.id)
            return ClaimAttempt.Retry
        }
        return ClaimAttempt.Claimed(candidate)
    }

    @Suppress("TooGenericExceptionCaught") // Throwable so executor rejection ALWAYS unwinds the slot.
    private fun submitToExecutor(candidate: MigrationJobState) {
        LOG.info("Starting migration job {}", candidate.id)
        try {
            executor.execute { runMigration(candidate.id) }
        } catch (rejected: Throwable) {
            // The slot was claimed for this candidate but the executor refused
            // (typical case: RejectedExecutionException during pod shutdown).
            // Without this rollback, the in-memory slot would stay RUNNING
            // forever — every subsequent POST /admin/migrate would return 409
            // and the operator would be wedged. Transition to FAILED so the
            // slot is "done" and the next startAsync claims a fresh one.
            LOG.error("Failed to submit migration job {} to executor", candidate.id, rejected)
            lifecycleGate.release(candidate.id)
            state.updateAndGet { current ->
                if (current?.id != candidate.id) {
                    current
                } else {
                    current.copy(
                        state = JobState.FAILED,
                        finishedAt = Instant.now(),
                        errorMessage =
                            "Failed to submit migration: ${rejected.message ?: rejected::class.java.simpleName}",
                        phase = null,
                    )
                }
            }
            throw rejected
        }
    }

    override fun current(): MigrationJobState? = state.get()

    private fun runMigration(jobId: String) {
        try {
            // phase=DEFAULTS was already published by startAsync(); we don't re-set it
            // here because a) it's already correct, b) re-publishing would mean two
            // observable phase-transitions for one logical phase boundary.
            val defaults = importService.migrateDefaults()

            updatePhase(jobId, MigrationPhase.COMPONENTS)
            val components = importService.migrateAllComponents(progressListener(jobId))

            val result = FullMigrationResult(defaults = defaults, components = components)
            state.updateAndGet { current ->
                // Defend against a stale jobId update (e.g. another start happened in
                // between, which shouldn't be possible while we're RUNNING but is cheap
                // to guard against).
                if (current?.id != jobId) {
                    current
                } else {
                    current.copy(
                        state = JobState.COMPLETED,
                        finishedAt = Instant.now(),
                        currentComponent = null,
                        phase = null,
                        result = result,
                        total = components.total,
                        migrated = components.migrated,
                        failed = components.failed,
                        skipped = components.skipped,
                    )
                }
            }
            LOG.info(
                "Migration job {} COMPLETED: {}/{} migrated, {} failed, {} skipped",
                jobId,
                components.migrated,
                components.total,
                components.failed,
                components.skipped,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("Migration job {} FAILED", jobId, e)
            state.updateAndGet { current ->
                if (current?.id != jobId) {
                    current
                } else {
                    current.copy(
                        state = JobState.FAILED,
                        finishedAt = Instant.now(),
                        currentComponent = null,
                        phase = null,
                        errorMessage = e.message ?: e::class.java.simpleName,
                    )
                }
            }
        } finally {
            // Always release the cross-kind gate, even on failure / unhandled throw,
            // so a follow-up history migration isn't blocked forever by a leaked claim.
            lifecycleGate.release(jobId)
        }
    }

    private fun updatePhase(
        jobId: String,
        nextPhase: MigrationPhase,
    ) {
        state.updateAndGet { current ->
            if (current?.id != jobId) {
                current
            } else {
                // Clear currentComponent on phase boundary so the SPA doesn't keep showing
                // a stale per-component label while the new phase is initializing.
                current.copy(phase = nextPhase, currentComponent = null)
            }
        }
    }

    private fun progressListener(jobId: String): MigrationProgressListener =
        MigrationProgressListener { event ->
            state.updateAndGet { current ->
                if (current?.id != jobId) {
                    current
                } else {
                    current.copy(
                        currentComponent = event.componentName,
                        migrated = event.migrated,
                        failed = event.failed,
                        skipped = event.skipped,
                        total = event.total,
                    )
                }
            }
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(MigrationJobServiceImpl::class.java)
    }
}
