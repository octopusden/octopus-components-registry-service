package org.octopusden.octopus.components.registry.server.service.rms

import jakarta.annotation.PreDestroy
import org.octopusden.octopus.components.registry.core.exceptions.RMSRegisteredValueConflictException
import org.octopusden.octopus.components.registry.core.exceptions.RMSUnavailableException
import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.octopusden.octopus.components.registry.server.dto.v4.ActualRange
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import org.octopusden.octopus.components.registry.server.util.VersionRangePartition
import org.springframework.stereotype.Service
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Live write-time check for `build.javaVersion`/`build.mavenVersion`: unlike
 * [RMSBuildParametersService], this never reads the display cache — every call makes its own
 * synchronous [RMSClient] call, using the same unfiltered fetch shape as the sweep. Registered
 * unconditionally (no direct JPA dependency of its own) — both collaborators are already nullable
 * and this degrades to a no-op the same way whether they're absent because the feature is
 * disabled or because no-db mode dropped [RMSBuildParametersService].
 */
@Service
class RMSOverrideGate(
    private val rmsClient: RMSClient?,
    private val rmsBuildParametersService: RMSBuildParametersService?,
    private val properties: RMSProperties,
) {
    // A write endpoint is expected to make at most one of these calls at a time per request thread,
    // so a small cached pool (not sized to sweepConcurrency) is enough; sized generously since an
    // idle thread here costs nothing and this must never itself become a bottleneck under write load.
    private val executor = Executors.newCachedThreadPool()

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    /**
     * Throws [RMSRegisteredValueConflictException] if [newValue] for [newRange] disagrees with
     * RMS's live ACTUAL data for an intersecting range; throws [RMSUnavailableException] if the
     * live call fails, times out (bounded by [RMSProperties.writeGateTimeout] — deliberately
     * tighter than the sweep's own timeouts, since this call runs inside the write's transaction),
     * or is ambiguous. Returns normally (permits the write) without ever calling RMS when: the
     * feature is disabled/unconfigured, [effectiveChange] is false, the write clears the value to
     * `null`, or [buildSystem] isn't Maven/Gradle.
     *
     * [buildsCache] lets callers share one RMS fetch per component across every gated field of a
     * single write (e.g. a `PATCH` gating both `javaVersion` and `mavenVersion`).
     */
    fun check(
        componentKey: String,
        buildSystem: String?,
        effectiveChange: Boolean,
        newValue: String?,
        newRange: String,
        selectValue: (RMSBuild) -> String?,
        valuesEqual: (String, String) -> Boolean,
        compare: (String, String) -> Int,
        buildsCache: MutableMap<String, List<RMSBuild>> = mutableMapOf(),
    ) {
        if (!properties.enabled) return
        if (!effectiveChange) return
        if (newValue == null) return
        if (buildSystem != "MAVEN" && buildSystem != "GRADLE") return
        val client = rmsClient ?: return

        val builds = buildsCache.getOrPut(componentKey) { fetchWithBudget(client, componentKey) }

        val actualRanges = BuildRangeCollapser.collapse(
            builds.map {
                BuildRangeCollapser.Build(it.version, selectValue(it))
            },
            valuesEqual = valuesEqual,
        )

        // A composite/malformed newRange can't be intersected, and VersionRangeIntersector reads that
        // as "no overlap" — fine for display's fail-soft warnings, wrong here: against non-empty ACTUAL
        // data, "couldn't evaluate" must fail closed, not silently permit.
        if (actualRanges.isNotEmpty() && !isSingleRange(newRange)) {
            throw RMSUnavailableException(
                "Could not confirm RMS's registered value for component '$componentKey': the range '$newRange' " +
                    "is composite or malformed, so it cannot be checked against RMS's non-empty ACTUAL data",
            )
        }

        val disagreements = RegisteredBuildParametersMapper.warnings(
            listOf(ActualRange(newRange, newValue)),
            actualRanges,
            valuesEqual,
            compare,
        )

        if (disagreements.isNotEmpty()) {
            rmsBuildParametersService?.refreshComponent(componentKey, builds)
            throw RMSRegisteredValueConflictException(
                "Component '$componentKey': writing '$newValue' for $newRange disagrees with RMS's registered value(s): " +
                    disagreements.joinToString { "${it.subRange}=${it.actualValue}" },
            )
        }
    }

    /** A single interval or the all-versions sentinel — [VersionRangeIntersector] can only evaluate this shape. */
    private fun isSingleRange(range: String): Boolean =
        VersionRangePartition.isAllVersions(range) || VersionRangePartition.parseSegment(range) != null

    private fun fetchWithBudget(
        client: RMSClient,
        componentKey: String,
    ): List<RMSBuild> {
        val future = executor.submit(Callable { client.getBuilds(componentKey) })
        val result =
            try {
                future.get(properties.writeGateTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                RMSBuildsResult.Unavailable
            } catch (_: Exception) {
                RMSBuildsResult.Unavailable
            }
        return when (result) {
            is RMSBuildsResult.Available -> result.builds
            RMSBuildsResult.Unavailable ->
                throw RMSUnavailableException(
                    "Could not confirm RMS's registered value for component '$componentKey' — the live check failed or timed out",
                )
        }
    }
}
