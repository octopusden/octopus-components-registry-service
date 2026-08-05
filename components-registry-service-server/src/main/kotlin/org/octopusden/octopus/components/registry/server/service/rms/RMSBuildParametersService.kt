package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import org.octopusden.octopus.components.registry.server.util.JavaVersionComparator
import org.octopusden.octopus.components.registry.server.util.MavenVersionComparator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ComponentBuildRanges(
    val javaRanges: List<BuildRangeCollapser.Run>,
    val mavenRanges: List<BuildRangeCollapser.Run>,
)

/**
 * [components] holds one entry per successfully-swept component (present even if both range
 * lists are empty). [unavailableComponents] holds every eligible component whose individual RMS
 * lookup failed or timed out during the sweep that produced this report — disjoint from [components].
 */
data class RMSBuildParametersReport(
    val generatedAt: Instant?,
    val lastAttemptAt: Instant?,
    val refreshError: String?,
    val components: Map<String, ComponentBuildRanges>,
    val unavailableComponents: Set<String>,
)

/**
 * Orchestrates the RMS build-parameters sweep and caches the result. Mirrors Portal's
 * `ValidationService` shape (single-flight guarded refresh, stale-but-honest retention on
 * failure), adapted to CRS's blocking stack and extended with exponential retry backoff.
 *
 * Always registered; every operation short-circuits on [RMSProperties.enabled] rather than
 * relying on conditional bean registration, so a disabled environment pays only a cheap no-op
 * check per would-be sweep, never a missing-bean wiring failure.
 */
@Service
class RMSBuildParametersService(
    private val rmsClient: RMSClient?,
    private val eligibleComponentsProvider: EligibleComponentsProvider,
    private val properties: RMSProperties,
) {
    @Volatile
    private var report = RMSBuildParametersReport(null, null, null, emptyMap(), emptySet())

    private val refreshing = AtomicBoolean(false)
    private val consecutiveFailures = AtomicInteger(0)

    fun currentReport(): RMSBuildParametersReport = report

    /** Delay before the next sweep: the normal interval after success, a doubling retry cadence (capped) after failure. */
    fun nextDelay(): Duration {
        if (report.refreshError == null) return properties.normalInterval
        val multiplier = 1L shl (consecutiveFailures.get() - 1).coerceAtLeast(0)
        val backoff = properties.initialRetryInterval.multipliedBy(multiplier)
        return if (backoff > properties.retryBackoffCap) properties.retryBackoffCap else backoff
    }

    /** Invoked by the scheduler's dynamic trigger. Single-flight guarded via [refresh]. */
    fun scheduledRefresh() {
        refresh()
    }

    fun refresh() {
        if (!properties.enabled) return
        val client = rmsClient ?: return
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("RMS build-parameters refresh already running, skipping this trigger")
            return
        }
        try {
            val fresh =
                runCatching { sweep(client) }
                    .onSuccess { consecutiveFailures.set(0) }
                    .onFailure { e ->
                        log.warn("RMS build-parameters sweep failed, retaining previous data: {}", e.toString())
                        consecutiveFailures.incrementAndGet()
                    }
            report =
                fresh.getOrElse { e ->
                    report.copy(lastAttemptAt = Instant.now(), refreshError = e.javaClass.simpleName)
                }
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * One full sweep: eligible components fetched with [RMSProperties.sweepConcurrency] in-flight
     * calls at a time, bounded overall by [RMSProperties.sweepTimeout]. A per-component failure
     * (an [RMSBuildsResult.Unavailable] result, an exception, or a timeout) marks only that
     * component unavailable — it never fails the whole sweep. Only a failure listing the eligible
     * components themselves propagates and fails the sweep.
     */
    private fun sweep(client: RMSClient): RMSBuildParametersReport {
        val eligible = eligibleComponentsProvider.listEligibleComponents()
        val executor = Executors.newFixedThreadPool(properties.sweepConcurrency.coerceAtLeast(1))
        try {
            val futures: List<Pair<String, Future<RMSBuildsResult>>> =
                eligible.map { component -> component to executor.submit(Callable { client.getBuilds(component) }) }

            val components = mutableMapOf<String, ComponentBuildRanges>()
            val unavailable = mutableSetOf<String>()
            val deadline = System.nanoTime() + properties.sweepTimeout.toNanos()

            for ((component, future) in futures) {
                val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0)
                try {
                    when (val result = future.get(remainingNanos, TimeUnit.NANOSECONDS)) {
                        is RMSBuildsResult.Available -> components[component] = collapse(result.builds)
                        RMSBuildsResult.Unavailable -> unavailable += component
                    }
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    unavailable += component
                } catch (_: Exception) {
                    unavailable += component
                }
            }

            val now = Instant.now()
            return RMSBuildParametersReport(now, now, null, components, unavailable)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun collapse(builds: List<RMSBuild>): ComponentBuildRanges =
        ComponentBuildRanges(
            javaRanges =
                BuildRangeCollapser.collapse(
                    builds.map { BuildRangeCollapser.Build(it.version, it.javaVersion) },
                    valuesEqual = JavaVersionComparator::valuesEqual,
                ),
            mavenRanges =
                BuildRangeCollapser.collapse(
                    builds.map { BuildRangeCollapser.Build(it.version, it.mavenVersion) },
                    valuesEqual = MavenVersionComparator::valuesEqual,
                ),
        )

    private companion object {
        private val log = LoggerFactory.getLogger(RMSBuildParametersService::class.java)
    }
}
