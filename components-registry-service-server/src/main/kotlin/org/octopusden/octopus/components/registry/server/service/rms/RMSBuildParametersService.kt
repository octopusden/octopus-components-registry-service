package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.ConditionalOnDatabaseEnabled
import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.octopusden.octopus.components.registry.server.service.rms.client.RMSBuild
import org.octopusden.octopus.components.registry.server.service.rms.client.RMSBuildsResult
import org.octopusden.octopus.components.registry.server.service.rms.client.RMSClient
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
 * [components] holds the last known-good ranges for every eligible component that has ever
 * succeeded at least once — a component whose latest sweep failed keeps its previous entry here
 * (stale-but-honest, mirroring the whole-sweep retention on [RMSBuildParametersService.refresh]).
 * [unavailableComponents] holds only eligible components that have **never** had a successful
 * lookup — disjoint from [components]. A component that drops out of eligibility (archived, or no
 * longer Maven/Gradle) has no entry in either.
 */
data class RMSBuildParametersReport(
    val generatedAt: Instant?,
    val lastAttemptAt: Instant?,
    val refreshError: String?,
    val components: Map<String, ComponentBuildRanges>,
    val unavailableComponents: Set<String>,
    /** How long the last *completed* sweep took. `null` before any sweep has completed. */
    val lastSweepDuration: Duration? = null,
)

/**
 * Orchestrates the RMS build-parameters sweep and caches the result. Mirrors Portal's
 * `ValidationService` shape (single-flight guarded refresh, stale-but-honest retention on
 * failure), adapted to CRS's blocking stack and extended with exponential retry backoff.
 *
 * Registered whenever the DB layer is (every operation short-circuits on [RMSProperties.enabled]
 * rather than relying on a second conditional for that — a disabled environment pays only a cheap
 * no-op check per would-be sweep). Absent entirely in no-db mode: [EligibleComponentsProvider]
 * is JPA-backed, so there is no component list to sweep without a database. Callers hold this via a
 * nullable, defaulted constructor param (see `ComponentManagementServiceImpl`) and treat its absence
 * the same as the feature being disabled.
 */
@Service
@ConditionalOnDatabaseEnabled
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

    /**
     * Whether the feature is turned on at all — distinct from [currentReport] ever having data.
     * A disabled integration never sweeps, so its report stays permanently empty; callers need
     * this to tell "disabled" apart from "enabled but never successfully swept," which otherwise
     * look identical from [currentReport] alone.
     */
    fun isEnabled(): Boolean = properties.enabled

    /**
     * Targeted refresh: update this one component's cache entry directly, using
     * [builds] already fetched by the write gate's own live call — no full sweep triggered. Called
     * after [RMSOverrideGate] rejects a write, so the display doesn't keep showing a stale, clean
     * row for a component whose conflict the cache hadn't caught up to yet.
     */
    fun refreshComponent(
        componentKey: String,
        builds: List<RMSBuild>,
    ) {
        // Read-then-write on `report`, not CAS-retried: a scheduled sweep completing in the same
        // instant could race this and be overwritten. Accepted — the next sweep (minutes to hours
        // away) corrects it either way, the same stale-but-honest tolerance the rest of this cache
        // already relies on, and a write-rejection racing a sweep is rare.
        val current = report
        report =
            current.copy(
                components = current.components + (componentKey to collapse(builds)),
                unavailableComponents = current.unavailableComponents - componentKey,
            )
    }

    /**
     * Delay before the next sweep: the normal interval after success, a doubling retry cadence
     * (capped) after failure. The shift exponent is bounded well below 63 — `1L shl 63` overflows
     * `Duration.multipliedBy` (`ArithmeticException`) long before the result would ever matter,
     * since `initialRetryInterval * 2^32` already dwarfs any realistic `retryBackoffCap`.
     */
    fun nextDelay(): Duration {
        if (report.refreshError == null) return properties.normalInterval
        val exponent = (consecutiveFailures.get() - 1).coerceIn(0, 32)
        val backoff = properties.initialRetryInterval.multipliedBy(1L shl exponent)
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
     *
     * A per-component failure retains that component's previous [RMSBuildParametersReport.components]
     * entry, if it has one, instead of dropping it — only a component with no prior successful
     * lookup at all lands in [RMSBuildParametersReport.unavailableComponents].
     */
    private fun sweep(client: RMSClient): RMSBuildParametersReport {
        val sweepStartNanos = System.nanoTime()
        val eligible = eligibleComponentsProvider.listEligibleComponents()
        val previousComponents = report.components
        val executor = Executors.newFixedThreadPool(properties.sweepConcurrency.coerceAtLeast(1))
        try {
            val futures: List<Pair<String, Future<RMSBuildsResult>>> =
                eligible.map { component -> component to executor.submit(Callable { client.getBuilds(component) }) }

            val components = mutableMapOf<String, ComponentBuildRanges>()
            val unavailable = mutableSetOf<String>()
            val deadline = System.nanoTime() + properties.sweepTimeout.toNanos()

            for ((component, future) in futures) {
                val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0)
                val result =
                    try {
                        future.get(remainingNanos, TimeUnit.NANOSECONDS)
                    } catch (_: TimeoutException) {
                        future.cancel(true)
                        null
                    } catch (_: Exception) {
                        null
                    }

                when {
                    result is RMSBuildsResult.Available -> components[component] = collapse(result.builds)
                    previousComponents.containsKey(component) -> components[component] = previousComponents.getValue(component)
                    else -> unavailable += component
                }
            }

            val now = Instant.now()
            val duration = Duration.ofNanos(System.nanoTime() - sweepStartNanos)
            return RMSBuildParametersReport(now, now, null, components, unavailable, duration)
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
