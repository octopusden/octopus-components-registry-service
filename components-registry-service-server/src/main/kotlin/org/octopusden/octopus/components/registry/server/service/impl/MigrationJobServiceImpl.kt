package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.service.AsyncJobLifecycle
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationJobService
import org.octopusden.octopus.components.registry.server.service.MigrationJobState
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind
import org.octopusden.octopus.components.registry.server.service.MigrationPhase
import org.octopusden.octopus.components.registry.server.service.MigrationProgressListener
import org.octopusden.octopus.components.registry.server.service.NoOpServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventRecorder
import org.octopusden.octopus.components.registry.server.service.ServiceEventSource
import org.octopusden.octopus.components.registry.server.service.ServiceEventStatus
import org.octopusden.octopus.components.registry.server.service.ServiceEventType
import org.octopusden.octopus.components.registry.server.service.StartMigrationResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * In-memory async wrapper around [ImportService.migrate]. Lifecycle boilerplate
 * (CAS+gate claim, executor submit-with-rollback, id-guarded state updates) is
 * delegated to [AsyncJobLifecycle]; this class is the components-specific
 * adapter — it owns the [MigrationJobState] DTO, phase model, and progress
 * listener wiring.
 *
 * Why in-memory and not table-backed: a single CRS pod owns the migration; a
 * hard restart kills the run regardless of where state lives. The
 * `git_history_import_state` pattern earns its keep when the operation is
 * genuinely resumable — `ImportService.migrate` would re-do every component on
 * restart, so we trade resilience for code we can read in one sitting.
 *
 * Concurrency model:
 *  - Same-kind 409 (a second components POST while one is RUNNING) is decided
 *    by the lifecycle's local in-memory slot: the SPA "attaches" to the
 *    in-flight job rather than spawning a duplicate.
 *  - Cross-kind serialization (a components POST while history is RUNNING) is
 *    decided by [MigrationLifecycleGate] which the lifecycle consults before
 *    claiming. ThreadPoolTaskExecutor on its own does NOT serialize cross-service:
 *    it queues the second submit, so without the gate both `startAsync` calls
 *    would return 202 and the SPA would render two RUNNING jobs.
 */
@ConditionalOnDatabaseEnabled
@Service
class MigrationJobServiceImpl(
    private val importService: ImportService,
    @Qualifier("migrationExecutor") executor: TaskExecutor,
    lifecycleGate: MigrationLifecycleGate,
    private val serviceEventRecorder: ServiceEventRecorder = NoOpServiceEventRecorder,
) : MigrationJobService {
    private val lifecycle =
        AsyncJobLifecycle<MigrationJobState>(
            jobKind = JobKind.COMPONENTS,
            executor = executor,
            gate = lifecycleGate,
            getId = { it.id },
            isRunning = { it.state == JobState.RUNNING },
            markRejected = { current, rejected ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    errorMessage =
                        "Failed to submit migration: ${rejected.message ?: rejected::class.java.simpleName}",
                    phase = null,
                )
            },
        )

    override fun startAsync(triggeredBy: String): StartMigrationResult {
        val outcome =
            try {
                lifecycle.claimAndSubmit(
                    buildCandidate = ::buildCandidate,
                    work = { jobId -> runMigration(jobId, triggeredBy) },
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") rejected: Throwable,
            ) {
                // Executor rejected submission (pod shutdown): runnable never ran, so
                // recordStart never fired. Record a standalone terminal FAILED (SYS-060).
                serviceEventRecorder.recordInstant(
                    type = ServiceEventType.MIGRATION_COMPONENTS,
                    source = ServiceEventSource.CRS,
                    triggeredBy = triggeredBy,
                    status = ServiceEventStatus.FAILED,
                    summary = "Components migration failed to start",
                    detail = mapOf("errorMessage" to (rejected.message ?: rejected::class.java.simpleName)),
                )
                throw rejected
            }
        return when (outcome) {
            is AsyncJobLifecycle.ClaimOutcome.Attached -> StartMigrationResult(outcome.state, isNewlyStarted = false)
            is AsyncJobLifecycle.ClaimOutcome.Started -> StartMigrationResult(outcome.state, isNewlyStarted = true)
        }
    }

    override fun current(): MigrationJobState? = lifecycle.current()

    /**
     * Build the freshly-RUNNING candidate seeded with `phase=DEFAULTS`.
     *
     * CRITICAL: phase is set on the candidate BEFORE executor.execute, not inside
     * the runnable. ThreadPoolTaskExecutor.execute returns synchronously and the
     * controller builds its response from `current()` right after — if phase
     * weren't already DEFAULTS the first SPA poll tick would see RUNNING with no
     * phase and render the fallback "Running…" instead of "Loading defaults from
     * Git…".
     */
    private fun buildCandidate(jobId: String): MigrationJobState =
        MigrationJobState(
            id = jobId,
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
            phase = MigrationPhase.DEFAULTS,
        )

    private fun runMigration(
        jobId: String,
        triggeredBy: String,
    ) {
        serviceEventRecorder.recordStart(
            type = ServiceEventType.MIGRATION_COMPONENTS,
            source = ServiceEventSource.CRS,
            triggeredBy = triggeredBy,
            correlationId = jobId,
            summary = "Components migration running",
        )
        try {
            // phase=DEFAULTS was already published by buildCandidate(); we don't
            // re-set it here because a) it's already correct, b) re-publishing
            // would mean two observable phase-transitions for one logical
            // phase boundary.
            val defaults = importService.migrateDefaults()

            lifecycle.update(jobId) { current ->
                // Clear currentComponent on phase boundary so the SPA doesn't keep
                // showing a stale per-component label while the new phase is initializing.
                current.copy(phase = MigrationPhase.COMPONENTS, currentComponent = null)
            }
            val components = importService.migrateAllComponents(progressListener(jobId))

            val result = FullMigrationResult(defaults = defaults, components = components)
            lifecycle.update(jobId) { current ->
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
            LOG.info(
                "Migration job {} COMPLETED: {}/{} migrated, {} failed, {} skipped",
                jobId,
                components.migrated,
                components.total,
                components.failed,
                components.skipped,
            )
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.MIGRATION_COMPONENTS,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.COMPLETED,
                summary = "Components migration completed",
                detail =
                    mapOf(
                        "total" to components.total,
                        "migrated" to components.migrated,
                        "failed" to components.failed,
                        "skipped" to components.skipped,
                    ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            LOG.error("Migration job {} FAILED", jobId, e)
            lifecycle.update(jobId) { current ->
                current.copy(
                    state = JobState.FAILED,
                    finishedAt = Instant.now(),
                    currentComponent = null,
                    phase = null,
                    errorMessage = e.message ?: e::class.java.simpleName,
                )
            }
            serviceEventRecorder.recordFinish(
                type = ServiceEventType.MIGRATION_COMPONENTS,
                source = ServiceEventSource.CRS,
                triggeredBy = triggeredBy,
                correlationId = jobId,
                status = ServiceEventStatus.FAILED,
                summary = "Components migration failed",
                detail = mapOf("errorMessage" to (e.message ?: e::class.java.simpleName)),
            )
        } finally {
            // Always release the cross-kind gate, even on failure / unhandled
            // throw, so a follow-up history migration isn't blocked forever by
            // a leaked claim.
            lifecycle.release(jobId)
        }
    }

    private fun progressListener(jobId: String): MigrationProgressListener =
        MigrationProgressListener { event ->
            lifecycle.update(jobId) { current ->
                current.copy(
                    currentComponent = event.componentName,
                    migrated = event.migrated,
                    failed = event.failed,
                    skipped = event.skipped,
                    total = event.total,
                )
            }
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(MigrationJobServiceImpl::class.java)
    }
}
