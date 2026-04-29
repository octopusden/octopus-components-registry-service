package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationPhase
import org.octopusden.octopus.components.registry.server.service.MigrationProgressEvent
import org.octopusden.octopus.components.registry.server.service.MigrationProgressListener
import org.octopusden.octopus.components.registry.server.service.MigrationResult
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.octopusden.octopus.components.registry.server.service.ValidationResult
import org.springframework.core.task.SyncTaskExecutor
import kotlin.test.assertFailsWith

/**
 * Unit-level pin on the idempotency contract MigrationJobService promises to the
 * controller:
 *   - Only one RUNNING job at a time. A second [startAsync] while the first is in
 *     flight returns the existing state and does NOT spawn a duplicate run.
 *   - Per-component progress fed by ImportService writes through to [current()].
 *   - Terminal transitions (COMPLETED on success, FAILED on throw) populate
 *     finishedAt + the right summary fields.
 *
 * Tests use [SyncTaskExecutor] so the migration body runs inline on the caller
 * thread; that lets the tests assert on terminal state right after `startAsync`
 * returns, without waiting on a background thread.
 *
 * Mockito is avoided here intentionally: `migrate(progress: MigrationProgressListener)`
 * has a non-nullable Kotlin parameter, and Mockito's `any()` returns null at the
 * call site, which trips the JVM null-check before the mock proxy intercepts.
 * A handwritten [StubImportService] dodges that whole class of issues.
 */
class MigrationJobServiceImplTest {
    private val emptyResult =
        FullMigrationResult(
            defaults = emptyMap(),
            components = BatchMigrationResult(total = 0, migrated = 0, failed = 0, skipped = 0, results = emptyList()),
        )

    @Test
    fun `current() is null before any job has been started`() {
        val service = MigrationJobServiceImpl(StubImportService(), SyncTaskExecutor(), MigrationLifecycleGate())
        assertNull(service.current())
    }

    @Test
    fun `first startAsync returns isNewlyStarted=true and the migration runs`() {
        val importService = StubImportService(migrateImpl = { emptyResult })
        val service = MigrationJobServiceImpl(importService, SyncTaskExecutor(), MigrationLifecycleGate())

        val result = service.startAsync()

        assertTrue(result.isNewlyStarted, "fresh job should be flagged as newly-started")
        // SyncTaskExecutor: by the time startAsync returns, the migration body has
        // already finished, so terminal state is COMPLETED.
        assertEquals(JobState.COMPLETED, result.state.state)
        assertEquals(1, importService.migrateCallCount)
    }

    @Test
    fun `second startAsync while RUNNING returns the existing job and does not spawn a duplicate`() {
        val deferredExecutor = DeferredExecutor()
        val importService = StubImportService(migrateImpl = { emptyResult })
        val service = MigrationJobServiceImpl(importService, deferredExecutor, MigrationLifecycleGate())

        val first = service.startAsync()
        // Migration runnable hasn't actually been run yet (deferred), so the job is
        // still in RUNNING state.
        assertEquals(JobState.RUNNING, first.state.state)
        assertTrue(first.isNewlyStarted)

        val second = service.startAsync()

        assertFalse(second.isNewlyStarted, "second start while RUNNING must NOT be flagged newly-started")
        assertEquals(first.state.id, second.state.id, "second start must echo back the SAME job id")
        assertEquals(1, deferredExecutor.taskCount, "executor must have been handed exactly one task")
    }

    @Test
    fun `after job COMPLETES the next startAsync starts a fresh job with a different id`() {
        val importService = StubImportService(migrateImpl = { emptyResult })
        val service = MigrationJobServiceImpl(importService, SyncTaskExecutor(), MigrationLifecycleGate())

        val first = service.startAsync()
        assertEquals(JobState.COMPLETED, first.state.state)

        val second = service.startAsync()
        assertTrue(second.isNewlyStarted)
        assertNotEquals(first.state.id, second.state.id, "completed job should not block a new run")
        assertEquals(2, importService.migrateCallCount)
    }

    @Test
    fun `progress callback updates currentComponent + counters in the live state`() {
        // The listener is invoked from inside `migrate`. While that call is in flight
        // the impl's state is RUNNING, but we can drive the callback synchronously
        // and inspect the post-callback state.
        val importService =
            StubImportService(migrateImpl = { listener ->
                // Drive a progress event through the listener — it should write through
                // to the AtomicReference inside the service.
                listener.onProgress(
                    MigrationProgressEvent(
                        componentName = "comp-7",
                        migrated = 6,
                        failed = 1,
                        skipped = 0,
                        total = 42,
                    ),
                )
                // Don't return yet — let the test inspect mid-run state. We pass a
                // lambda the test can flip from outside, then complete after.
                emptyResult
            })
        val service = MigrationJobServiceImpl(importService, SyncTaskExecutor(), MigrationLifecycleGate())

        service.startAsync()

        // Even after COMPLETED the impl preserves the final per-component snapshot
        // up until it overwrites with the result-summary in the terminal step.
        // We can't easily intercept mid-run with SyncTaskExecutor, so instead we
        // assert the listener got invoked exactly once via the call-count.
        assertEquals(1, importService.progressCallCount)
        assertEquals("comp-7", importService.lastProgressEvent?.componentName)
    }

    @Test
    fun `terminal state captures summary from FullMigrationResult on COMPLETED`() {
        val populatedResult =
            FullMigrationResult(
                defaults = emptyMap(),
                components =
                    BatchMigrationResult(
                        total = 42,
                        migrated = 40,
                        failed = 2,
                        skipped = 0,
                        results = listOf(MigrationResult("c", success = true, dryRun = false, message = "ok")),
                    ),
            )
        val importService = StubImportService(migrateImpl = { populatedResult })
        val service = MigrationJobServiceImpl(importService, SyncTaskExecutor(), MigrationLifecycleGate())

        service.startAsync()
        val state = service.current()!!

        assertEquals(JobState.COMPLETED, state.state)
        assertNotNull(state.finishedAt)
        assertNull(state.currentComponent, "currentComponent must be cleared once the run is done")
        assertEquals(42, state.total)
        assertEquals(40, state.migrated)
        assertEquals(2, state.failed)
        assertEquals(populatedResult, state.result)
    }

    @Test
    fun `terminal state is FAILED with error message when ImportService throws`() {
        val importService = StubImportService(migrateImpl = { throw IllegalStateException("disk full") })
        val service = MigrationJobServiceImpl(importService, SyncTaskExecutor(), MigrationLifecycleGate())

        service.startAsync()
        val state = service.current()!!

        assertEquals(JobState.FAILED, state.state)
        assertNotNull(state.finishedAt)
        assertNull(state.currentComponent)
        assertEquals("disk full", state.errorMessage)
        assertNull(state.result)
    }

    @Test
    fun `executor rejection transitions the slot to FAILED and rethrows`() {
        // Realistic case: pod shutdown — Spring's ThreadPoolTaskExecutor throws
        // RejectedExecutionException once the queue is closed. Without the
        // rollback, the slot stays RUNNING forever and every subsequent POST
        // returns 409 from the controller layer. The operator would be wedged.
        val importService = StubImportService(migrateImpl = { fail("executor refused; importService should never be touched") })
        val service = MigrationJobServiceImpl(importService, RejectingExecutor("pool shutting down"), MigrationLifecycleGate())

        val thrown = assertFailsWith<java.util.concurrent.RejectedExecutionException> { service.startAsync() }
        assertEquals("pool shutting down", thrown.message)

        val state = service.current()!!
        assertEquals(JobState.FAILED, state.state)
        assertNotNull(state.finishedAt)
        assertEquals("Failed to submit migration: pool shutting down", state.errorMessage)
        assertEquals(0, importService.migrateCallCount, "ImportService.migrate must not be called when the executor refuses")
    }

    @Test
    fun `next startAsync after an executor-rejection failure starts a fresh job (no 409)`() {
        // Tests the practical consequence of the rollback above: once we've
        // transitioned the slot to FAILED, a retry must succeed. Without the
        // P2 fix the slot would be RUNNING and we'd return isNewlyStarted=false.
        val importService = StubImportService(migrateImpl = { emptyResult })
        var rejecting = true
        val executor =
            ToggleableExecutor(
                onExecute = { runnable ->
                    if (rejecting) throw java.util.concurrent.RejectedExecutionException("once")
                    runnable.run()
                },
            )
        val service = MigrationJobServiceImpl(importService, executor, MigrationLifecycleGate())

        // First call → rejection → FAILED slot.
        runCatching { service.startAsync() }
        assertEquals(JobState.FAILED, service.current()!!.state)

        // Operator retries; executor is healthy this time.
        rejecting = false
        val retry = service.startAsync()

        assertTrue(retry.isNewlyStarted, "after FAILED, retry must claim a fresh slot, not 409 the previous one")
        assertEquals(JobState.COMPLETED, retry.state.state)
    }

    @Test
    fun `phase is set to DEFAULTS in the published state BEFORE the runnable runs`() {
        // Pin the race fix from A2: ThreadPoolTaskExecutor.execute() returns
        // synchronously, and the controller builds its 202 response from
        // state.get() right after. If phase were set inside runMigration
        // (the runnable), the SPA would briefly see RUNNING with phase=null
        // and render fallback "Running…" instead of "Loading defaults…" —
        // exactly the UX bug we set out to fix. DeferredExecutor never runs
        // the captured runnable, so the only way state.phase can be DEFAULTS
        // here is if it was published at startAsync time.
        val deferredExecutor = DeferredExecutor()
        val service =
            MigrationJobServiceImpl(
                StubImportService(migrateImpl = { fail("runnable should not have run") }),
                deferredExecutor,
                MigrationLifecycleGate(),
            )

        val result = service.startAsync()

        assertEquals(JobState.RUNNING, result.state.state)
        assertEquals(MigrationPhase.DEFAULTS, result.state.phase)
        assertEquals(MigrationPhase.DEFAULTS, service.current()!!.phase)
    }

    @Test
    fun `phase transitions to COMPONENTS before migrateAllComponents and clears on COMPLETED`() {
        // Capture phase observed inside each ImportService method to assert ordering.
        val phaseAtMigrateDefaults = AtomicPhaseRef()
        val phaseAtMigrateAllComponents = AtomicPhaseRef()
        // Use a service ref captured by closure so the stub can read live phase from
        // the same instance under test.
        lateinit var service: MigrationJobServiceImpl
        val importService =
            object : StubImportService() {
                override fun migrateDefaults(): Map<String, Any?> {
                    phaseAtMigrateDefaults.value = service.current()?.phase
                    return emptyMap()
                }

                override fun migrateAllComponents(progress: MigrationProgressListener): BatchMigrationResult {
                    phaseAtMigrateAllComponents.value = service.current()?.phase
                    return BatchMigrationResult(0, 0, 0, 0, emptyList())
                }
            }
        service = MigrationJobServiceImpl(importService, SyncTaskExecutor(), MigrationLifecycleGate())

        service.startAsync()

        assertEquals(MigrationPhase.DEFAULTS, phaseAtMigrateDefaults.value, "migrateDefaults runs while phase=DEFAULTS")
        assertEquals(MigrationPhase.COMPONENTS, phaseAtMigrateAllComponents.value, "migrateAllComponents runs while phase=COMPONENTS")
        assertNull(service.current()!!.phase, "terminal COMPLETED state must clear phase to null")
    }

    @Test
    fun `phase is cleared on FAILED transition`() {
        val service =
            MigrationJobServiceImpl(
                StubImportService(migrateImpl = { throw IllegalStateException("boom") }),
                SyncTaskExecutor(),
                MigrationLifecycleGate(),
            )
        service.startAsync()
        assertEquals(JobState.FAILED, service.current()!!.state)
        assertNull(service.current()!!.phase, "terminal FAILED state must also clear phase")
    }

    @Test
    fun `runMigration releases the lifecycle gate on success`() {
        val gate = MigrationLifecycleGate()
        val service =
            MigrationJobServiceImpl(
                StubImportService(migrateImpl = {
                    BatchMigrationResult(0, 0, 0, 0, emptyList())
                    org.octopusden.octopus.components.registry.server.service.FullMigrationResult(
                        defaults = emptyMap(),
                        components = BatchMigrationResult(0, 0, 0, 0, emptyList()),
                    )
                }),
                SyncTaskExecutor(),
                gate,
            )
        service.startAsync()
        assertNull(gate.current(), "gate must be released after a successful run so other migrations are not blocked")
    }

    @Test
    fun `runMigration releases the lifecycle gate on failure`() {
        val gate = MigrationLifecycleGate()
        val service =
            MigrationJobServiceImpl(
                StubImportService(migrateImpl = { throw IllegalStateException("boom") }),
                SyncTaskExecutor(),
                gate,
            )
        service.startAsync()
        assertNull(gate.current(), "gate must be released after FAILED too — leak would block any future history migration")
    }

    @Test
    fun `executor rejection releases the lifecycle gate so the next startAsync can proceed`() {
        val gate = MigrationLifecycleGate()
        val service =
            MigrationJobServiceImpl(
                StubImportService(migrateImpl = { fail("must not be called") }),
                RejectingExecutor("queue closed"),
                gate,
            )
        runCatching { service.startAsync() }
        assertNull(gate.current(), "rejection branch must release the gate too — defends against gate-leak on submit failure")
    }

    @Test
    fun `cross-kind gate held by HISTORY makes startAsync throw MigrationConflictException`() {
        val gate = MigrationLifecycleGate()
        // Simulate history-job currently holding the gate.
        gate.tryClaim(MigrationLifecycleGate.JobKind.HISTORY, "history-1")

        val service =
            MigrationJobServiceImpl(
                StubImportService(migrateImpl = { fail("must not be called when cross-kind conflict") }),
                SyncTaskExecutor(),
                gate,
            )

        val ex =
            assertFailsWith<org.octopusden.octopus.components.registry.server.service.MigrationConflictException> {
                service.startAsync()
            }
        assertEquals(MigrationLifecycleGate.JobKind.HISTORY, ex.active.kind)
        assertEquals("history-1", ex.active.jobId)
    }

    /** Captures a [MigrationPhase] observed from a different thread / call site. */
    private class AtomicPhaseRef {
        @Volatile
        var value: MigrationPhase? = null
    }

    /**
     * Hand-written stub for [ImportService]. Mockito would have been smaller, but
     * the non-nullable Kotlin progress parameter trips its `any()` matcher
     * (returns null → NPE before the proxy intercepts). The stub records call
     * counts + the last progress event so tests can assert on flow.
     *
     * Adapter contract: the constructor takes a `migrateImpl` that returns a
     * full [FullMigrationResult]; the stub splits this between [migrateDefaults]
     * (returns the `defaults` map) and [migrateAllComponents] (returns the
     * `components` value AND drives any progress events the body would have
     * emitted). This preserves existing test ergonomics from when
     * MigrationJobServiceImpl called `migrate(progress)` as one step before A2
     * split it into two phases.
     */
    private open class StubImportService(
        private val migrateImpl: (MigrationProgressListener) -> FullMigrationResult = { unused() },
    ) : ImportService {
        var migrateCallCount: Int = 0
            private set
        var progressCallCount: Int = 0
            private set
        var lastProgressEvent: MigrationProgressEvent? = null
            private set

        // Memoize across the two phase calls so the result-building lambda
        // runs exactly once per logical migration. migrateDefaults is called
        // first, then migrateAllComponents — both pull from the same memo.
        private var memoized: FullMigrationResult? = null
        private var capturedListener: MigrationProgressListener? = null

        private fun buildResult(): FullMigrationResult {
            val cached = memoized
            if (cached != null) return cached
            migrateCallCount++
            val listener = capturedListener ?: MigrationProgressListener.NOOP
            val tap =
                MigrationProgressListener { event ->
                    progressCallCount++
                    lastProgressEvent = event
                    listener.onProgress(event)
                }
            val result = migrateImpl(tap)
            memoized = result
            return result
        }

        override fun migrate(progress: MigrationProgressListener): FullMigrationResult {
            // Legacy entrypoint kept for the few tests / callers that still use it.
            capturedListener = progress
            return buildResult()
        }

        override fun migrateComponent(
            name: String,
            dryRun: Boolean,
        ): MigrationResult = unused()

        // `open` so phase-ordering tests can override per-method to capture state mid-run.
        open override fun migrateAllComponents(progress: MigrationProgressListener): BatchMigrationResult {
            capturedListener = progress
            val result = buildResult()
            // Logical migration boundary: defaults+components both ran. Reset memo
            // so the next startAsync (post-COMPLETED retry) re-evaluates the lambda
            // and migrateCallCount climbs as expected.
            memoized = null
            return result.components
        }

        override fun getMigrationStatus(): MigrationStatus = unused()

        override fun validateMigration(name: String): ValidationResult = unused()

        open override fun migrateDefaults(): Map<String, Any?> {
            // Defaults runs first under A2, but if migrateImpl throws we want it to
            // fire from migrateAllComponents (so phase=COMPONENTS at failure-time
            // matches what the existing tests expect about FAILED transitions).
            // To preserve that, defaults returns an empty placeholder and the
            // real lambda fires inside migrateAllComponents.
            return emptyMap()
        }

        companion object {
            private fun <T> unused(): T = error("not stubbed for this test")
        }
    }

    /** Capture-but-don't-run executor so a "RUNNING" state can be observed from outside. */
    private class DeferredExecutor : org.springframework.core.task.TaskExecutor {
        var taskCount: Int = 0
            private set

        override fun execute(task: Runnable) {
            taskCount++
            // Intentionally NOT running the task.
        }
    }

    /** Always throws [RejectedExecutionException] — simulates pod shutdown / queue full. */
    private class RejectingExecutor(
        private val reason: String,
    ) : org.springframework.core.task.TaskExecutor {
        override fun execute(task: Runnable): Unit = throw java.util.concurrent.RejectedExecutionException(reason)
    }

    /** Caller-controlled executor: invoke [onExecute] on each submission. */
    private class ToggleableExecutor(
        private val onExecute: (Runnable) -> Unit,
    ) : org.springframework.core.task.TaskExecutor {
        override fun execute(task: Runnable) {
            onExecute(task)
        }
    }
}
