package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationJobState
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
 * The single-thread [TaskExecutor] is the ground truth for "no two migrations at
 * once": even if the AtomicReference CAS were buggy, the executor's queue serializes
 * runs. The CAS exists for the *response*: we want the second `POST /admin/migrate`
 * to return 409 immediately rather than queue a duplicate run that would only fail
 * later with "already migrated".
 */
@Service
class MigrationJobServiceImpl(
    private val importService: ImportService,
    @Qualifier("migrationExecutor") private val executor: TaskExecutor,
) : MigrationJobService {
    private val state = AtomicReference<MigrationJobState?>()

    override fun startAsync(): StartMigrationResult {
        // CAS loop: claim the slot ONLY if no RUNNING job is currently in it.
        while (true) {
            val existing = state.get()
            if (existing?.state == JobState.RUNNING) {
                return StartMigrationResult(existing, isNewlyStarted = false)
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
                )
            if (state.compareAndSet(existing, candidate)) {
                LOG.info("Starting migration job {}", candidate.id)
                try {
                    executor.execute { runMigration(candidate.id) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") rejected: Throwable,
                ) {
                    // The slot was claimed for this candidate but the executor refused
                    // (typical case: RejectedExecutionException during pod shutdown,
                    // or a queue-capacity-exceeded if a future config narrows the
                    // queue). Without this, the in-memory slot would stay RUNNING
                    // forever — every subsequent POST /admin/migrate would return
                    // 409, and the operator would be stuck without a way to retry.
                    // Transition to FAILED so the slot is "done" and the next
                    // startAsync claims a fresh one.
                    LOG.error("Failed to submit migration job {} to executor", candidate.id, rejected)
                    state.updateAndGet { current ->
                        if (current?.id != candidate.id) {
                            current
                        } else {
                            current.copy(
                                state = JobState.FAILED,
                                finishedAt = Instant.now(),
                                errorMessage =
                                    "Failed to submit migration: ${rejected.message ?: rejected::class.java.simpleName}",
                            )
                        }
                    }
                    throw rejected
                }
                // Return the post-spawn current state — with SyncTaskExecutor (used in
                // tests) the run has already finished by now, so callers see COMPLETED;
                // with the production async executor they see RUNNING. Either way it's
                // the authoritative state for "what is the job right now".
                return StartMigrationResult(state.get() ?: candidate, isNewlyStarted = true)
            }
            // Lost the CAS race against a concurrent caller; fall through to re-check.
        }
    }

    override fun current(): MigrationJobState? = state.get()

    private fun runMigration(jobId: String) {
        try {
            val result = importService.migrate(progressListener(jobId))
            val components = result.components
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
                        errorMessage = e.message ?: e::class.java.simpleName,
                    )
                }
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
