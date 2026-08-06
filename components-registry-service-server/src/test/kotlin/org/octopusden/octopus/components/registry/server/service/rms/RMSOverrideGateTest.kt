package org.octopusden.octopus.components.registry.server.service.rms

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.exceptions.RMSRegisteredValueConflictException
import org.octopusden.octopus.components.registry.core.exceptions.RMSUnavailableException
import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import org.octopusden.octopus.components.registry.server.util.JavaVersionComparator
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class RMSOverrideGateTest {
    private fun props(
        enabled: Boolean = true,
        writeGateTimeout: Duration = Duration.ofSeconds(3),
    ) = RMSProperties(enabled = enabled, url = "http://rms.example.com", writeGateTimeout = writeGateTimeout)

    private fun gate(
        client: RMSClient?,
        service: RMSBuildParametersService? = null,
        enabled: Boolean = true,
        writeGateTimeout: Duration = Duration.ofSeconds(3),
    ) = RMSOverrideGate(client, service, props(enabled, writeGateTimeout))

    private fun check(
        gate: RMSOverrideGate,
        buildSystem: String? = "MAVEN",
        effectiveChange: Boolean = true,
        newValue: String? = "11",
        newRange: String = "[1,3)",
    ) {
        gate.check(
            componentKey = "comp-a",
            buildSystem = buildSystem,
            effectiveChange = effectiveChange,
            newValue = newValue,
            newRange = newRange,
            selectValue = { it.javaVersion },
            valuesEqual = JavaVersionComparator::valuesEqual,
            compare = JavaVersionComparator::compare,
        )
    }

    @Test
    @DisplayName("no effective change permits the write without ever calling RMS")
    fun `no effective change permits without calling RMS`() {
        val callCount = AtomicInteger(0)
        val client = RMSClient { callCount.incrementAndGet(); RMSBuildsResult.Unavailable }
        assertDoesNotThrow { check(gate(client), effectiveChange = false) }
        assertEquals(0, callCount.get())
    }

    @Test
    @DisplayName("clearing the value to null permits the write without calling RMS")
    fun `clearing to null permits without calling RMS`() {
        val callCount = AtomicInteger(0)
        val client = RMSClient { callCount.incrementAndGet(); RMSBuildsResult.Unavailable }
        assertDoesNotThrow { check(gate(client), newValue = null) }
        assertEquals(0, callCount.get())
    }

    @Test
    @DisplayName("a disabled integration permits the write without calling RMS")
    fun `disabled integration permits without calling RMS`() {
        val callCount = AtomicInteger(0)
        val client = RMSClient { callCount.incrementAndGet(); RMSBuildsResult.Unavailable }
        assertDoesNotThrow { check(gate(client, enabled = false)) }
        assertEquals(0, callCount.get())
    }

    @Test
    @DisplayName("no RMSClient bean permits the write without throwing (defensive short-circuit)")
    fun `null client permits without throwing`() {
        assertDoesNotThrow { check(gate(null)) }
    }

    @Test
    @DisplayName("a component whose build system is not Maven or Gradle permits the write without calling RMS")
    fun `non-Maven-Gradle build system permits without calling RMS`() {
        val callCount = AtomicInteger(0)
        val client = RMSClient { callCount.incrementAndGet(); RMSBuildsResult.Unavailable }
        assertDoesNotThrow { check(gate(client), buildSystem = "GOLANG") }
        assertEquals(0, callCount.get())
    }

    @Test
    @DisplayName("ECLIPSE_MAVEN is not treated as Maven — permits the write without calling RMS")
    fun `ECLIPSE_MAVEN permits without calling RMS`() {
        val callCount = AtomicInteger(0)
        val client = RMSClient { callCount.incrementAndGet(); RMSBuildsResult.Unavailable }
        assertDoesNotThrow { check(gate(client), buildSystem = "ECLIPSE_MAVEN") }
        assertEquals(0, callCount.get())
    }

    @Test
    @DisplayName("a null build system permits the write without calling RMS")
    fun `null build system permits without calling RMS`() {
        val callCount = AtomicInteger(0)
        val client = RMSClient { callCount.incrementAndGet(); RMSBuildsResult.Unavailable }
        assertDoesNotThrow { check(gate(client), buildSystem = null) }
        assertEquals(0, callCount.get())
    }

    @Test
    @DisplayName("a Gradle build system is not short-circuited — a disagreeing value is still rejected")
    fun `GRADLE build system is gated the same as Maven`() {
        val client = RMSClient { RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))) }
        assertThrows(RMSRegisteredValueConflictException::class.java) {
            check(gate(client), buildSystem = "GRADLE", newValue = "11", newRange = "[1,5)")
        }
    }

    @Test
    @DisplayName("ACTUAL null for the range (confirmed empty response) permits the write")
    fun `confirmed empty response permits the write`() {
        val client = RMSClient { RMSBuildsResult.Available(emptyList()) }
        assertDoesNotThrow { check(gate(client)) }
    }

    @Test
    @DisplayName("writing a value that matches ACTUAL is permitted")
    fun `a matching value is permitted`() {
        val client = RMSClient { RMSBuildsResult.Available(listOf(RMSBuild("1", "11", null))) }
        assertDoesNotThrow { check(gate(client), newValue = "11") }
    }

    @Test
    @DisplayName("writing a value that disagrees with ACTUAL is rejected")
    fun `a disagreeing value is rejected`() {
        val client = RMSClient { RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))) }
        assertThrows(RMSRegisteredValueConflictException::class.java) { check(gate(client), newValue = "11", newRange = "[1,5)") }
    }

    @Test
    @DisplayName("an unreachable/ambiguous live call fails closed with RMSUnavailableException")
    fun `an unavailable result fails closed`() {
        val client = RMSClient { RMSBuildsResult.Unavailable }
        assertThrows(RMSUnavailableException::class.java) { check(gate(client)) }
    }

    @Test
    @DisplayName("a client that throws instead of returning Unavailable still fails closed with RMSUnavailableException")
    fun `a throwing client fails closed`() {
        val client = RMSClient { throw IllegalStateException("connection refused") }
        assertThrows(RMSUnavailableException::class.java) { check(gate(client)) }
    }

    @Test
    @DisplayName("a call exceeding the write-gate timeout budget fails closed rather than blocking the write indefinitely")
    fun `a slow call exceeding the timeout budget fails closed`() {
        val client =
            RMSClient {
                Thread.sleep(2000)
                RMSBuildsResult.Available(emptyList())
            }
        val start = System.nanoTime()
        assertThrows(RMSUnavailableException::class.java) {
            check(gate(client, writeGateTimeout = Duration.ofMillis(100)))
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(true, elapsedMs < 2000, "the check must not wait for the full 2s call; took ${elapsedMs}ms")
    }

    @Test
    @DisplayName("a rejection triggers a targeted cache refresh for that one component, using the same live data")
    fun `a rejection refreshes the one component's cache entry`() {
        val builds = listOf(RMSBuild("2", "17", null))
        val client = RMSClient { RMSBuildsResult.Available(builds) }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { emptyList() }, props())

        assertThrows(RMSRegisteredValueConflictException::class.java) {
            check(gate(client, service), newValue = "11", newRange = "[1,5)")
        }

        val ranges = service.currentReport().components["comp-a"]
        assertEquals(listOf(BuildRangeCollapser.Run("[2,)", "17")), ranges?.javaRanges)
    }

    @Test
    @DisplayName("a rejection with no RMSBuildParametersService present does not throw from the refresh step")
    fun `a rejection with no service present still throws the conflict, not an NPE`() {
        val client = RMSClient { RMSBuildsResult.Available(listOf(RMSBuild("2", "17", null))) }
        assertThrows(RMSRegisteredValueConflictException::class.java) {
            check(gate(client, service = null), newValue = "11", newRange = "[1,5)")
        }
    }
}
