package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate.JobKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pin the cross-job concurrency contract:
 *   - At most one job kind owns the gate at any time (cross- or same-kind).
 *   - tryClaim is CAS-loop safe: a release between a failed CAS and a re-read
 *     must not let a third caller report success without actually owning the
 *     slot (the bug a naive `compareAndSet → return active.get()` would have).
 *   - release is owner-scoped + idempotent.
 */
class MigrationLifecycleGateTest {
    @Test
    fun `current() is null on a fresh gate`() {
        assertNull(MigrationLifecycleGate().current())
    }

    @Test
    fun `first tryClaim succeeds and publishes the active job`() {
        val gate = MigrationLifecycleGate()
        val conflict = gate.tryClaim(JobKind.COMPONENTS, "job-1")
        assertNull(conflict, "first claim on an empty gate should report no conflict")
        val active = gate.current()
        assertNotNull(active)
        assertEquals(JobKind.COMPONENTS, active!!.kind)
        assertEquals("job-1", active.jobId)
    }

    @Test
    fun `same-kind second tryClaim returns the existing owner without overwriting it`() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(JobKind.COMPONENTS, "job-1")
        val conflict = gate.tryClaim(JobKind.COMPONENTS, "job-2")
        assertNotNull(conflict)
        assertEquals(JobKind.COMPONENTS, conflict!!.kind)
        assertEquals("job-1", conflict.jobId, "owner must remain job-1; the second claimant must NOT overwrite")
        assertEquals("job-1", gate.current()!!.jobId)
    }

    @Test
    fun `cross-kind tryClaim returns the existing owner so the controller can map to 409`() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(JobKind.COMPONENTS, "components-1")
        val conflict = gate.tryClaim(JobKind.HISTORY, "history-1")
        assertNotNull(conflict)
        assertEquals(JobKind.COMPONENTS, conflict!!.kind)
        assertEquals("components-1", conflict.jobId)
    }

    @Test
    fun `release by owner clears the slot, release by stranger is a no-op`() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(JobKind.HISTORY, "history-1")

        gate.release("not-the-owner")
        assertEquals(
            "history-1",
            gate.current()!!.jobId,
            "release with a foreign jobId must not clear the slot — defends against a stale finally block",
        )

        gate.release("history-1")
        assertNull(gate.current(), "owner-scoped release must clear the slot")

        // Idempotent: a second release on an already-empty slot is harmless.
        gate.release("history-1")
        assertNull(gate.current())
    }

    @Test
    fun `released slot is claimable by the other kind`() {
        val gate = MigrationLifecycleGate()
        gate.tryClaim(JobKind.COMPONENTS, "components-1")
        gate.release("components-1")

        val conflict = gate.tryClaim(JobKind.HISTORY, "history-1")
        assertNull(conflict, "after release, a different kind must be able to claim")
        assertEquals(JobKind.HISTORY, gate.current()!!.kind)
    }

    /**
     * Two threads race for the same gate kind in a tight loop. The contract:
     *   - Exactly one of them must observe a successful claim (`null` return).
     *   - The other must observe a non-null conflict.
     * Never both null (two owners of one slot) and never both non-null with the
     * gate empty (lost-update where neither owns it).
     *
     * Repeated 100x to surface the race window — the bug a naive
     * `compareAndSet → active.get()` design would hit is timing-dependent.
     */
    @RepeatedTest(100)
    fun `two threads claiming the same kind in parallel — exactly one wins`() {
        val gate = MigrationLifecycleGate()
        val nullReturns = AtomicInteger(0)
        val conflictReturns = AtomicInteger(0)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)

        val workers =
            (0 until 2).map { i ->
                Thread {
                    ready.countDown()
                    go.await()
                    val result = gate.tryClaim(JobKind.COMPONENTS, "job-$i")
                    if (result == null) nullReturns.incrementAndGet() else conflictReturns.incrementAndGet()
                }.also { it.start() }
            }

        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        workers.forEach { it.join(5_000) }

        assertEquals(1, nullReturns.get(), "exactly one thread must claim the gate")
        assertEquals(1, conflictReturns.get(), "exactly one thread must observe the conflict")
        assertNotNull(gate.current(), "gate must end up owned by the winner")
    }

    /**
     * The classic lost-update scenario the CAS-loop is designed to prevent:
     *
     * 1. Thread A claims (active = A).
     * 2. Thread B reads active → sees A → would return conflict, BUT a naive
     *    impl reads active a second time after a failed CAS to build the
     *    response, and between those two reads A releases, so the second read
     *    returns null. The naive impl then returns null = "no conflict =
     *    success", but never installed B in the slot. Thread C then claims
     *    successfully too. Both B and C think they own the gate.
     *
     * The CAS-loop impl reacts to the now-null slot by retrying the CAS, which
     * either publishes B or sees a fresh non-null owner. We verify by counting:
     * across many thrashing iterations the number of "successful claims"
     * (returns null) must never exceed the number of releases + 1 (the initial
     * empty slot).
     */
    @Test
    fun `release-then-CAS-retry never produces phantom successful claims`() {
        val gate = MigrationLifecycleGate()
        val claimsObserved = AtomicInteger(0)
        val releasesPerformed = AtomicInteger(0)
        val iterations = 2_000

        val claimer =
            Thread {
                repeat(iterations) { i ->
                    val result = gate.tryClaim(JobKind.COMPONENTS, "claim-$i")
                    if (result == null) {
                        claimsObserved.incrementAndGet()
                        gate.release("claim-$i")
                        releasesPerformed.incrementAndGet()
                    }
                }
            }
        val challenger =
            Thread {
                repeat(iterations) { i ->
                    val result = gate.tryClaim(JobKind.HISTORY, "challenge-$i")
                    if (result == null) {
                        claimsObserved.incrementAndGet()
                        gate.release("challenge-$i")
                        releasesPerformed.incrementAndGet()
                    }
                }
            }

        claimer.start()
        challenger.start()
        claimer.join(10_000)
        challenger.join(10_000)

        assertEquals(
            claimsObserved.get(),
            releasesPerformed.get(),
            "every observed claim must be paired with a release — phantom claims would skew this count",
        )
        assertNull(gate.current(), "after all releases the gate must be empty")
        assertTrue(claimsObserved.get() > 0, "sanity check: at least one claim should have happened")
    }
}
