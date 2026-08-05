package org.octopusden.octopus.components.registry.server.service.rms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RMSBuildParametersServiceTest {
    private fun props(
        enabled: Boolean = true,
        normalInterval: Duration = Duration.ofHours(4),
        initialRetryInterval: Duration = Duration.ofMinutes(5),
        retryBackoffCap: Duration = Duration.ofHours(4),
        sweepConcurrency: Int = 4,
        sweepTimeout: Duration = Duration.ofSeconds(5),
    ) = RMSProperties(
        enabled = enabled,
        url = "http://rms.example.com",
        normalInterval = normalInterval,
        initialRetryInterval = initialRetryInterval,
        retryBackoffCap = retryBackoffCap,
        sweepConcurrency = sweepConcurrency,
        sweepTimeout = sweepTimeout,
    )

    @Test
    @DisplayName("a full sweep populates the cache with generatedAt/lastAttemptAt and both attributes' collapsed ranges")
    fun `full sweep populates the cache with collapsed ranges`() {
        val builds = listOf(RMSBuild("1", javaVersion = "17", mavenVersion = "3.3.6"), RMSBuild("2", javaVersion = "17", mavenVersion = "3.3.6"))
        val client = RMSClient { RMSBuildsResult.Available(builds) }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("comp-a") }, props())

        service.refresh()

        val report = service.currentReport()
        assertNotNull(report.generatedAt)
        assertNotNull(report.lastAttemptAt)
        assertNull(report.refreshError)
        val ranges = report.components.getValue("comp-a")
        assertEquals(listOf(BuildRangeCollapser.Run("[1,)", "17")), ranges.javaRanges)
        assertEquals(listOf(BuildRangeCollapser.Run("[1,)", "3.3.6")), ranges.mavenRanges)
    }

    @Test
    @DisplayName("a failed sweep retains previous data and sets refreshError")
    fun `a failed sweep retains previous data and sets refreshError`() {
        val client = RMSClient { RMSBuildsResult.Available(listOf(RMSBuild("1", "17", null))) }
        var shouldFail = false
        val provider = EligibleComponentsProvider { if (shouldFail) throw RuntimeException("boom") else listOf("comp-a") }
        val service = RMSBuildParametersService(client, provider, props())

        service.refresh()
        val goodReport = service.currentReport()

        shouldFail = true
        service.refresh()
        val failedReport = service.currentReport()

        assertEquals(goodReport.components, failedReport.components)
        assertEquals(goodReport.generatedAt, failedReport.generatedAt)
        assertNotNull(failedReport.refreshError)
    }

    @Test
    @DisplayName("the single-flight guard rejects an overlapping trigger")
    fun `single-flight guard rejects an overlapping trigger`() {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val callCount = AtomicInteger(0)
        val client =
            RMSClient {
                callCount.incrementAndGet()
                startedLatch.countDown()
                releaseLatch.await(5, TimeUnit.SECONDS)
                RMSBuildsResult.Available(emptyList())
            }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("comp-a") }, props())

        val background = Thread { service.refresh() }
        background.start()
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))

        service.refresh()

        releaseLatch.countDown()
        background.join(5000)
        assertEquals(1, callCount.get())
    }

    @Test
    @DisplayName("a per-component RMS failure marks only that component unavailable, without failing the whole sweep")
    fun `a per-component RMS failure marks only that component unavailable`() {
        val client =
            RMSClient { component ->
                if (component == "bad") RMSBuildsResult.Unavailable else RMSBuildsResult.Available(listOf(RMSBuild("1", "17", null)))
            }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("good", "bad") }, props())

        service.refresh()

        val report = service.currentReport()
        assertEquals(setOf("bad"), report.unavailableComponents)
        assertTrue(report.components.containsKey("good"))
        assertNull(report.refreshError)
    }

    @Test
    @DisplayName("a component whose call exceeds the sweep timeout budget is marked unavailable rather than left hanging")
    fun `a component exceeding the sweep timeout is marked unavailable`() {
        val client =
            RMSClient {
                Thread.sleep(2000)
                RMSBuildsResult.Available(emptyList())
            }
        val service =
            RMSBuildParametersService(
                client,
                EligibleComponentsProvider { listOf("slow") },
                props(sweepTimeout = Duration.ofMillis(100)),
            )

        val start = System.nanoTime()
        service.refresh()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val report = service.currentReport()
        assertEquals(setOf("slow"), report.unavailableComponents)
        assertTrue(report.components.isEmpty())
        assertTrue(elapsedMs < 2000, "sweep should not wait for the full 2s call; took ${elapsedMs}ms")
    }

    @Test
    @DisplayName("after a successful sweep, the next delay is the normal interval")
    fun `after success next delay is the normal interval`() {
        val service =
            RMSBuildParametersService(
                { RMSBuildsResult.Available(emptyList()) },
                { listOf("comp-a") },
                props(normalInterval = Duration.ofHours(4)),
            )
        service.refresh()
        assertEquals(Duration.ofHours(4), service.nextDelay())
    }

    @Test
    @DisplayName("after a failed sweep, the next delay starts at the initial retry interval")
    fun `after failure next delay starts at the initial retry interval`() {
        val provider = EligibleComponentsProvider { throw RuntimeException("boom") }
        val service =
            RMSBuildParametersService(
                RMSClient { RMSBuildsResult.Available(emptyList()) },
                provider,
                props(initialRetryInterval = Duration.ofMinutes(5)),
            )
        service.refresh()
        assertEquals(Duration.ofMinutes(5), service.nextDelay())
    }

    @Test
    @DisplayName("each consecutive failure doubles the retry interval, capped at the backoff cap")
    fun `each consecutive failure doubles the retry interval capped at the backoff cap`() {
        val provider = EligibleComponentsProvider { throw RuntimeException("boom") }
        val service =
            RMSBuildParametersService(
                RMSClient { RMSBuildsResult.Available(emptyList()) },
                provider,
                props(initialRetryInterval = Duration.ofMinutes(5), retryBackoffCap = Duration.ofMinutes(15)),
            )

        service.refresh()
        assertEquals(Duration.ofMinutes(5), service.nextDelay())
        service.refresh()
        assertEquals(Duration.ofMinutes(10), service.nextDelay())
        service.refresh()
        assertEquals(Duration.ofMinutes(15), service.nextDelay(), "would be 20m uncapped; must not exceed the 15m cap")
    }

    @Test
    @DisplayName("a success resets the backoff, so the next failure starts again at the initial retry interval")
    fun `a success resets the backoff`() {
        var shouldFail = true
        val provider = EligibleComponentsProvider { if (shouldFail) throw RuntimeException("boom") else listOf("comp-a") }
        val service =
            RMSBuildParametersService(
                RMSClient { RMSBuildsResult.Available(emptyList()) },
                provider,
                props(initialRetryInterval = Duration.ofMinutes(5), normalInterval = Duration.ofHours(4)),
            )

        service.refresh()
        service.refresh()
        assertEquals(Duration.ofMinutes(10), service.nextDelay())

        shouldFail = false
        service.refresh()
        assertEquals(Duration.ofHours(4), service.nextDelay())

        shouldFail = true
        service.refresh()
        assertEquals(Duration.ofMinutes(5), service.nextDelay(), "backoff must restart from the initial interval, not from 10m")
    }

    @Test
    @DisplayName("a disabled integration never sweeps")
    fun `a disabled integration never sweeps`() {
        val client = RMSClient { throw AssertionError("must not be called while disabled") }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("comp-a") }, props(enabled = false))

        service.refresh()

        val report = service.currentReport()
        assertNull(report.generatedAt)
        assertNull(report.lastAttemptAt)
        assertNull(report.refreshError)
        assertTrue(report.components.isEmpty())
        assertFalse(report.unavailableComponents.contains("comp-a"))
    }

    @Test
    @DisplayName("enabled but with no RMSClient bean present never sweeps (defensive short-circuit)")
    fun `enabled with a null RMSClient never sweeps`() {
        val service = RMSBuildParametersService(null, EligibleComponentsProvider { listOf("comp-a") }, props(enabled = true))

        service.refresh()

        val report = service.currentReport()
        assertNull(report.generatedAt)
        assertNull(report.lastAttemptAt)
        assertNull(report.refreshError)
        assertTrue(report.components.isEmpty())
    }

    @Test
    @DisplayName("before any sweep has ever run, the next delay is the normal interval")
    fun `next delay before any sweep is the normal interval`() {
        val service =
            RMSBuildParametersService(
                RMSClient { RMSBuildsResult.Available(emptyList()) },
                EligibleComponentsProvider { listOf("comp-a") },
                props(normalInterval = Duration.ofHours(4)),
            )
        assertEquals(Duration.ofHours(4), service.nextDelay())
    }

    @Test
    @DisplayName("a component whose call throws directly is isolated the same way as an Unavailable result")
    fun `a component whose call throws is marked unavailable, not left to fail the whole sweep`() {
        val client =
            RMSClient { component ->
                if (component == "throws") throw RuntimeException("boom") else RMSBuildsResult.Available(listOf(RMSBuild("1", "17", null)))
            }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("good", "throws") }, props())

        service.refresh()

        val report = service.currentReport()
        assertEquals(setOf("throws"), report.unavailableComponents)
        assertTrue(report.components.containsKey("good"))
        assertNull(report.refreshError, "one component throwing must not fail the whole sweep")
    }

    @Test
    @DisplayName("a component that recovers on a later sweep moves from unavailable into components")
    fun `a previously-unavailable component recovers on the next successful sweep`() {
        var componentIsUp = false
        val client =
            RMSClient {
                if (componentIsUp) RMSBuildsResult.Available(listOf(RMSBuild("1", "17", null))) else RMSBuildsResult.Unavailable
            }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("comp-a") }, props())

        service.refresh()
        assertEquals(setOf("comp-a"), service.currentReport().unavailableComponents)
        assertTrue(service.currentReport().components.isEmpty())

        componentIsUp = true
        service.refresh()

        val report = service.currentReport()
        assertTrue(report.unavailableComponents.isEmpty())
        assertTrue(report.components.containsKey("comp-a"))
    }

    @Test
    @DisplayName("a component that goes down on a later sweep keeps its last known-good data, and is never marked unavailable")
    fun `a previously-available component retains its stale data when a later sweep fails for it`() {
        var componentIsUp = true
        val client =
            RMSClient {
                if (componentIsUp) RMSBuildsResult.Available(listOf(RMSBuild("1", "17", null))) else RMSBuildsResult.Unavailable
            }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("comp-a") }, props())

        service.refresh()
        val goodRanges = service.currentReport().components.getValue("comp-a")

        componentIsUp = false
        service.refresh()

        val report = service.currentReport()
        assertEquals(goodRanges, report.components["comp-a"], "the last known-good ranges must be retained, not dropped")
        assertTrue(
            report.unavailableComponents.isEmpty(),
            "a component with prior good data must never be marked unavailable, per spec.md: " +
                "'shown as unavailable only for a component that has never had a successful sweep'",
        )
    }

    @Test
    @DisplayName("a component that becomes ineligible loses its cached data entirely, rather than serving it forever")
    fun `a component that drops out of eligibility is removed from the report`() {
        var eligible = true
        val client = RMSClient { RMSBuildsResult.Available(listOf(RMSBuild("1", "17", null))) }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { if (eligible) listOf("comp-a") else emptyList() }, props())

        service.refresh()
        assertTrue(service.currentReport().components.containsKey("comp-a"))

        eligible = false
        service.refresh()

        val report = service.currentReport()
        assertFalse(report.components.containsKey("comp-a"))
        assertFalse(report.unavailableComponents.contains("comp-a"))
    }

    @Test
    @DisplayName("after the single-flight guard rejects an overlapping call, a later non-overlapping refresh still runs")
    fun `refreshing resets after completion, so a later refresh is not permanently blocked`() {
        val callCount = AtomicInteger(0)
        val client =
            RMSClient {
                callCount.incrementAndGet()
                RMSBuildsResult.Available(emptyList())
            }
        val service = RMSBuildParametersService(client, EligibleComponentsProvider { listOf("comp-a") }, props())

        service.refresh()
        service.refresh()

        assertEquals(2, callCount.get(), "the guard must release after each refresh completes")
    }

    @Test
    @DisplayName("the sweep concurrency bound limits how many calls run at once")
    fun `sweep concurrency bound limits simultaneous in-flight calls`() {
        val inFlight = AtomicInteger(0)
        val maxObservedInFlight = AtomicInteger(0)
        val components = (1..8).map { "comp-$it" }
        val client =
            RMSClient {
                val current = inFlight.incrementAndGet()
                maxObservedInFlight.updateAndGet { previous -> maxOf(previous, current) }
                Thread.sleep(100)
                inFlight.decrementAndGet()
                RMSBuildsResult.Available(emptyList())
            }
        val service =
            RMSBuildParametersService(
                client,
                EligibleComponentsProvider { components },
                props(sweepConcurrency = 2, sweepTimeout = Duration.ofSeconds(5)),
            )

        service.refresh()

        assertTrue(
            maxObservedInFlight.get() <= 2,
            "at most 2 calls should ever run at once; observed ${maxObservedInFlight.get()}",
        )
        assertEquals(8, service.currentReport().components.size)
    }

    @Test
    @DisplayName("the overall sweep timeout budget bounds total time across several slow components, not just one")
    fun `sweep timeout budget bounds total time across multiple slow components`() {
        val components = (1..4).map { "comp-$it" }
        val client =
            RMSClient {
                Thread.sleep(2000)
                RMSBuildsResult.Available(emptyList())
            }
        val service =
            RMSBuildParametersService(
                client,
                EligibleComponentsProvider { components },
                props(sweepConcurrency = 1, sweepTimeout = Duration.ofMillis(200)),
            )

        val start = System.nanoTime()
        service.refresh()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(components.toSet(), service.currentReport().unavailableComponents)
        assertTrue(elapsedMs < 2000, "total sweep time must be bounded by the shared budget, not 4 x 2s; took ${elapsedMs}ms")
    }
}
