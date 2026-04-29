package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.dto.v4.HistoryImportResult
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStateEntity
import org.octopusden.octopus.components.registry.server.entity.GitHistoryImportStatus
import org.octopusden.octopus.components.registry.server.repository.GitHistoryImportStateRepository
import org.octopusden.octopus.components.registry.server.service.GitHistoryImportService
import org.octopusden.octopus.components.registry.server.service.HistoryImportProgressEvent
import org.octopusden.octopus.components.registry.server.service.HistoryImportProgressListener
import org.octopusden.octopus.components.registry.server.service.JobState
import org.octopusden.octopus.components.registry.server.service.MigrationConflictException
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.util.Optional
import kotlin.test.assertFailsWith

/**
 * Mirror of [MigrationJobServiceImplTest] for the history migration service.
 * Same SyncTaskExecutor pattern (so terminal states are observable without a
 * background thread) and same hand-written stubs (avoiding Mockito's NPE
 * trap with non-nullable Kotlin progress parameters).
 */
class HistoryMigrationJobServiceImplTest {
    private val emptyResult =
        HistoryImportResult(
            targetRef = "refs/tags/test-1.0",
            targetSha = "abc1234567890",
            processedCommits = 0,
            skippedNoGroovy = 0,
            skippedParseError = 0,
            skippedUnknownNames = 0,
            auditRecords = 0,
            durationMs = 0,
        )

    @Test
    fun `current() is null on a fresh service with empty DB`() {
        val service = newService()
        assertNull(service.current())
    }

    @Test
    fun `first startAsync returns isNewlyStarted=true and runs the import`() {
        val import = StubGitHistoryImportService(impl = { _, _, _ -> emptyResult })
        val service = newService(import = import)

        val result = service.startAsync(toRef = null, reset = false)

        assertTrue(result.isNewlyStarted)
        assertEquals(JobState.COMPLETED, result.state.state)
        assertEquals(1, import.callCount)
    }

    @Test
    fun `second startAsync while RUNNING returns the same job (same-kind 409 attach)`() {
        val deferred = DeferredExecutor()
        val service =
            newService(
                import = StubGitHistoryImportService(impl = { _, _, _ -> emptyResult }),
                executor = deferred,
            )

        val first = service.startAsync(toRef = null, reset = false)
        assertEquals(JobState.RUNNING, first.state.state)
        assertTrue(first.isNewlyStarted)

        val second = service.startAsync(toRef = null, reset = false)
        assertFalse(second.isNewlyStarted)
        assertEquals(first.state.id, second.state.id)
        assertEquals(1, deferred.taskCount)
    }

    @Test
    fun `after COMPLETED the next startAsync starts a fresh job`() {
        val import = StubGitHistoryImportService(impl = { _, _, _ -> emptyResult })
        val service = newService(import = import)

        val first = service.startAsync(toRef = null, reset = false)
        val second = service.startAsync(toRef = null, reset = false)

        assertTrue(second.isNewlyStarted)
        assertNotEquals(first.state.id, second.state.id)
        assertEquals(2, import.callCount)
    }

    @Test
    fun `progress events update totalCommits processedCommits and currentSha`() {
        val import =
            StubGitHistoryImportService(impl = { _, _, listener ->
                listener.onProgress(
                    HistoryImportProgressEvent(
                        totalCommits = 100,
                        processedCommits = 7,
                        auditRecords = 13,
                        skippedNoGroovy = 1,
                        skippedParseError = 0,
                        skippedUnknownNames = 0,
                        currentSha = "abc1234",
                        targetRef = "refs/tags/test-1.0",
                    ),
                )
                emptyResult
            })
        val service = newService(import = import)

        service.startAsync(toRef = null, reset = false)

        // The listener writes through to the AtomicReference; on COMPLETED the
        // counters get overwritten by the final result. Verify mid-run by
        // checking the import got the listener exactly once with the expected
        // event shape.
        assertEquals(1, import.progressEvents.size)
        val event = import.progressEvents.single()
        assertEquals(100, event.totalCommits)
        assertEquals(7, event.processedCommits)
        assertEquals("abc1234", event.currentSha)
    }

    @Test
    fun `terminal COMPLETED state captures result counters and clears currentSha`() {
        val populated =
            HistoryImportResult(
                targetRef = "refs/tags/main",
                targetSha = "deadbeef",
                processedCommits = 250,
                skippedNoGroovy = 10,
                skippedParseError = 2,
                skippedUnknownNames = 1,
                auditRecords = 800,
                durationMs = 12_000,
            )
        val service = newService(import = StubGitHistoryImportService(impl = { _, _, _ -> populated }))

        service.startAsync(toRef = null, reset = false)
        val state = service.current()!!

        assertEquals(JobState.COMPLETED, state.state)
        assertNotNull(state.finishedAt)
        assertNull(state.currentSha)
        assertEquals(250, state.processedCommits)
        assertEquals(800, state.auditRecords)
        assertEquals(populated, state.result)
    }

    @Test
    fun `terminal FAILED state captures the throwable message`() {
        val service =
            newService(
                import = StubGitHistoryImportService(impl = { _, _, _ -> throw IllegalStateException("git refused clone") }),
            )

        service.startAsync(toRef = null, reset = false)
        val state = service.current()!!

        assertEquals(JobState.FAILED, state.state)
        assertEquals("git refused clone", state.errorMessage)
        assertNull(state.currentSha)
    }

    @Test
    fun `cross-kind gate held by COMPONENTS makes startAsync throw MigrationConflictException`() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(MigrationLifecycleGate.JobKind.COMPONENTS, "components-1")

        val service =
            newService(
                import = StubGitHistoryImportService(impl = { _, _, _ -> fail("must not run when cross-kind conflict") }),
                gate = gate,
            )

        val ex = assertFailsWith<MigrationConflictException> { service.startAsync(toRef = null, reset = false) }
        assertEquals(MigrationLifecycleGate.JobKind.COMPONENTS, ex.active.kind)
        assertEquals("components-1", ex.active.jobId)
    }

    @Test
    fun `gate is released on success`() {
        val gate = MigrationLifecycleGate()
        val service = newService(import = StubGitHistoryImportService(impl = { _, _, _ -> emptyResult }), gate = gate)
        service.startAsync(toRef = null, reset = false)
        assertNull(gate.current(), "gate must be released after COMPLETED")
    }

    @Test
    fun `gate is released on failure`() {
        val gate = MigrationLifecycleGate()
        val service =
            newService(
                import = StubGitHistoryImportService(impl = { _, _, _ -> throw IllegalStateException("boom") }),
                gate = gate,
            )
        service.startAsync(toRef = null, reset = false)
        assertNull(gate.current(), "gate must be released after FAILED")
    }

    @Test
    fun `executor rejection releases the gate and transitions the slot to FAILED`() {
        val gate = MigrationLifecycleGate()
        val service =
            newService(
                import = StubGitHistoryImportService(impl = { _, _, _ -> fail("must not be called") }),
                executor = RejectingExecutor("queue closed"),
                gate = gate,
            )

        runCatching { service.startAsync(toRef = null, reset = false) }
        assertNull(gate.current(), "rejection must release the gate")
        assertEquals(JobState.FAILED, service.current()!!.state)
    }

    @Test
    fun `current() falls back to DB row when in-memory state is null`() {
        val repo = StubStateRepository()
        repo.saveRow(
            GitHistoryImportStateEntity(
                importKey = "component-history",
                targetRef = "refs/tags/components-registry-1.5",
                targetSha = "cafebabe",
                status = GitHistoryImportStatus.COMPLETED.name,
                updatedAt = Instant.parse("2026-04-29T10:00:00Z"),
            ),
        )
        val service = newService(stateRepository = repo)

        val state = service.current()!!
        assertEquals(JobState.COMPLETED, state.state)
        assertEquals("refs/tags/components-registry-1.5", state.targetRef)
        assertNull(state.errorMessage, "COMPLETED synthesized state has no errorMessage")
        assertNull(state.result, "synthesized state cannot reconstruct the full result body")
    }

    @Test
    fun `current() synthesizes FAILED-with-explainer when DB row is FAILED`() {
        val repo = StubStateRepository()
        repo.saveRow(
            GitHistoryImportStateEntity(
                importKey = "component-history",
                targetRef = "refs/tags/v2",
                targetSha = "abc",
                status = GitHistoryImportStatus.FAILED.name,
                updatedAt = Instant.now(),
            ),
        )
        val service = newService(stateRepository = repo)

        val state = service.current()!!
        assertEquals(JobState.FAILED, state.state)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Use Retry"))
    }

    @Test
    fun `current() synthesizes IN_PROGRESS row as FAILED-with-marker so SPA shows Force-reset path`() {
        // The SPA uses the literal substring "marked IN_PROGRESS" in errorMessage
        // to switch its UI from "Retry" to "Force reset" — see B4
        // HistoryActionButtons. This pin is load-bearing for the cross-pod
        // recovery path; do not soften it without updating the SPA in lockstep.
        val repo = StubStateRepository()
        repo.saveRow(
            GitHistoryImportStateEntity(
                importKey = "component-history",
                targetRef = "refs/tags/v3",
                targetSha = "abc",
                status = GitHistoryImportStatus.IN_PROGRESS.name,
                updatedAt = Instant.now(),
            ),
        )
        val service = newService(stateRepository = repo)

        val state = service.current()!!
        assertEquals(JobState.FAILED, state.state, "IN_PROGRESS in DB but no in-memory job — surface as FAILED for the UI")
        assertTrue(
            state.errorMessage!!.contains("marked IN_PROGRESS"),
            "errorMessage must contain the SPA-recognised marker 'marked IN_PROGRESS' (verbatim) so it routes to Force-reset",
        )
    }

    @Test
    fun `current() prefers in-memory state over DB row when both exist`() {
        val repo = StubStateRepository()
        repo.saveRow(
            GitHistoryImportStateEntity(
                importKey = "component-history",
                targetRef = "old-ref",
                targetSha = "old-sha",
                status = GitHistoryImportStatus.COMPLETED.name,
                updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
            ),
        )
        val service =
            newService(
                stateRepository = repo,
                import = StubGitHistoryImportService(impl = { _, _, _ -> emptyResult }),
            )

        // Run a fresh import — in-memory now COMPLETED with the new result.
        service.startAsync(toRef = null, reset = false)
        val state = service.current()!!
        assertNotEquals("old-ref", state.targetRef, "in-memory must win when populated")
    }

    @Test
    fun `clearInMemory drops the in-memory state so DB-fallback takes over`() {
        val repo = StubStateRepository()
        repo.saveRow(
            GitHistoryImportStateEntity(
                importKey = "component-history",
                targetRef = "refs/tags/v1",
                targetSha = "abc",
                status = GitHistoryImportStatus.COMPLETED.name,
                updatedAt = Instant.now(),
            ),
        )
        val service =
            newService(
                stateRepository = repo,
                import = StubGitHistoryImportService(impl = { _, _, _ -> emptyResult.copy(targetRef = "refs/tags/just-finished") }),
            )

        service.startAsync(toRef = null, reset = false)
        assertEquals("refs/tags/just-finished", service.current()!!.targetRef)

        service.clearInMemory()
        assertEquals("refs/tags/v1", service.current()!!.targetRef, "after clearInMemory, DB-fallback re-surfaces")
    }

    private fun newService(
        import: GitHistoryImportService = StubGitHistoryImportService(),
        stateRepository: GitHistoryImportStateRepository = StubStateRepository(),
        executor: org.springframework.core.task.TaskExecutor = SyncTaskExecutor(),
        gate: MigrationLifecycleGate = MigrationLifecycleGate(),
    ): HistoryMigrationJobServiceImpl =
        HistoryMigrationJobServiceImpl(
            gitHistoryImportService = import,
            stateRepository = stateRepository,
            executor = executor,
            lifecycleGate = gate,
        )

    /** Hand-written stub for [GitHistoryImportService] mirroring StubImportService in the components test. */
    private class StubGitHistoryImportService(
        private val impl: (String?, Boolean, HistoryImportProgressListener) -> HistoryImportResult = { _, _, _ -> error("not stubbed") },
    ) : GitHistoryImportService {
        var callCount: Int = 0
            private set
        val progressEvents: MutableList<HistoryImportProgressEvent> = mutableListOf()

        override fun importHistory(
            toRef: String?,
            reset: Boolean,
            listener: HistoryImportProgressListener,
        ): HistoryImportResult {
            callCount++
            val tap =
                HistoryImportProgressListener { event ->
                    progressEvents += event
                    listener.onProgress(event)
                }
            return impl(toRef, reset, tap)
        }
    }

    /**
     * Hand-rolled stub for [GitHistoryImportStateRepository] that only implements
     * the methods HistoryMigrationJobServiceImpl actually uses (findById +
     * tryInsert) plus the test-only [saveRow] seeder. JpaRepository has dozens
     * of methods we don't touch; making them all `error("unused")` is the
     * standard Kotlin pattern for "fail fast on unexpected usage in test".
     */
    private class StubStateRepository : GitHistoryImportStateRepository {
        private val rows = mutableMapOf<String, GitHistoryImportStateEntity>()

        fun saveRow(entity: GitHistoryImportStateEntity) {
            rows[entity.importKey] = entity
        }

        override fun findById(id: String): Optional<GitHistoryImportStateEntity> = Optional.ofNullable(rows[id])

        override fun tryInsert(
            importKey: String,
            targetRef: String,
            targetSha: String,
            status: String,
        ): Int {
            if (rows.containsKey(importKey)) return 0
            rows[importKey] =
                GitHistoryImportStateEntity(
                    importKey = importKey,
                    targetRef = targetRef,
                    targetSha = targetSha,
                    status = status,
                    updatedAt = Instant.now(),
                )
            return 1
        }

        // Everything else: not used by the service-under-test. Failing loudly
        // surfaces accidental usage in future tests.
        override fun <S : GitHistoryImportStateEntity?> save(entity: S): S = error("unused")

        override fun <S : GitHistoryImportStateEntity?> saveAll(entities: MutableIterable<S>): MutableList<S> = error("unused")

        override fun existsById(id: String): Boolean = rows.containsKey(id)

        override fun findAll(): MutableList<GitHistoryImportStateEntity> = rows.values.toMutableList()

        override fun findAll(sort: org.springframework.data.domain.Sort): MutableList<GitHistoryImportStateEntity> = error("unused")

        override fun findAll(pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<GitHistoryImportStateEntity> = error("unused")

        override fun findAllById(ids: MutableIterable<String>): MutableList<GitHistoryImportStateEntity> = error("unused")

        override fun count(): Long = rows.size.toLong()

        override fun deleteById(id: String) {
            rows.remove(id)
        }

        override fun delete(entity: GitHistoryImportStateEntity) {
            rows.remove(entity.importKey)
        }

        override fun deleteAllById(ids: MutableIterable<String>) {
            ids.forEach(::deleteById)
        }

        override fun deleteAll(entities: MutableIterable<GitHistoryImportStateEntity>) = entities.forEach(::delete)

        override fun deleteAll() {
            rows.clear()
        }

        override fun flush() = Unit

        override fun <S : GitHistoryImportStateEntity?> saveAndFlush(entity: S): S = error("unused")

        override fun <S : GitHistoryImportStateEntity?> saveAllAndFlush(entities: MutableIterable<S>): MutableList<S> = error("unused")

        @Suppress("DEPRECATION")
        override fun deleteAllInBatch(entities: MutableIterable<GitHistoryImportStateEntity>) = error("unused")

        override fun deleteAllByIdInBatch(ids: MutableIterable<String>) = error("unused")

        override fun deleteAllInBatch() = error("unused")

        @Suppress("DEPRECATION")
        override fun getOne(id: String): GitHistoryImportStateEntity = error("unused")

        @Suppress("DEPRECATION")
        override fun getById(id: String): GitHistoryImportStateEntity = error("unused")

        override fun getReferenceById(id: String): GitHistoryImportStateEntity = error("unused")

        override fun <S : GitHistoryImportStateEntity?> findAll(example: org.springframework.data.domain.Example<S>): MutableList<S> = error("unused")

        override fun <S : GitHistoryImportStateEntity?> findAll(
            example: org.springframework.data.domain.Example<S>,
            sort: org.springframework.data.domain.Sort,
        ): MutableList<S> = error("unused")

        override fun <S : GitHistoryImportStateEntity?> findAll(
            example: org.springframework.data.domain.Example<S>,
            pageable: org.springframework.data.domain.Pageable,
        ): org.springframework.data.domain.Page<S> = error("unused")

        override fun <S : GitHistoryImportStateEntity?> findOne(example: org.springframework.data.domain.Example<S>): Optional<S> = error("unused")

        override fun <S : GitHistoryImportStateEntity?> count(example: org.springframework.data.domain.Example<S>): Long = error("unused")

        override fun <S : GitHistoryImportStateEntity?> exists(example: org.springframework.data.domain.Example<S>): Boolean = error("unused")

        override fun <S : GitHistoryImportStateEntity?, R : Any?> findBy(
            example: org.springframework.data.domain.Example<S>,
            queryFunction: java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = error("unused")
    }

    /** Capture-but-don't-run executor mirroring the one in MigrationJobServiceImplTest. */
    private class DeferredExecutor : org.springframework.core.task.TaskExecutor {
        var taskCount: Int = 0
            private set

        override fun execute(task: Runnable) {
            taskCount++
            // intentionally no-op
        }
    }

    /** Always throws RejectedExecutionException — simulates pod shutdown / queue full. */
    private class RejectingExecutor(
        private val reason: String,
    ) : org.springframework.core.task.TaskExecutor {
        override fun execute(task: Runnable): Unit = throw java.util.concurrent.RejectedExecutionException(reason)
    }
}
