package org.octopusden.octopus.components.registry.server.util

/**
 * Collapses a build history into ranges: one run per stretch of consecutive builds carrying an
 * equal (normalized) value. A null or differing value ends the run before it. Only the run
 * containing the single highest-version build stays open-ended, and only if that build's value
 * is non-null.
 */
object RmsBuildRangeCollapser {
    data class Build(val version: String, val value: String?)

    data class Run(val versionRange: String, val value: String)

    /**
     * [hasUnparseableVersion] is true when at least one build's version could not be parsed via the
     * injected `parseVersion` — that build is excluded from [runs] rather than mis-ordered by a
     * silently-degrading fallback.
     */
    data class Result(
        val runs: List<Run>,
        val hasUnparseableVersion: Boolean,
    )

    /**
     * @param builds need not be pre-sorted — they are sorted here by [parseVersion].
     * @param parseVersion the real version ordering for [Build.version]. Throwing for a given
     *   version excludes that build and sets [Result.hasUnparseableVersion].
     * @param valuesEqual value equality after normalization; defaults to plain string equality.
     */
    fun <T : Comparable<T>> collapse(
        builds: List<Build>,
        parseVersion: (String) -> T,
        valuesEqual: (String, String) -> Boolean = String::equals,
    ): Result {
        if (builds.isEmpty()) return Result(emptyList(), false)

        var hasUnparseableVersion = false
        val parsed =
            builds.mapNotNull { build ->
                try {
                    build to parseVersion(build.version)
                } catch (_: Exception) {
                    hasUnparseableVersion = true
                    null
                }
            }
        val sorted = parsed.sortedBy { it.second }

        val runs = mutableListOf<Run>()
        var runStartVersion: String? = null
        var runValue: String? = null

        for ((build, _) in sorted) {
            val value = build.value
            val sameAsRun = runValue != null && value != null && valuesEqual(runValue, value)
            if (!sameAsRun) {
                if (runStartVersion != null) {
                    runs += Run(closedRange(runStartVersion, build.version), runValue!!)
                }
                runStartVersion = if (value != null) build.version else null
                runValue = value
            }
        }
        // The run still active after the walk contains the highest-version build.
        if (runStartVersion != null) {
            runs += Run(openRange(runStartVersion), runValue!!)
        }

        return Result(runs, hasUnparseableVersion)
    }

    private fun closedRange(
        start: String,
        end: String,
    ): String = VersionRangePartition.render(VersionRangePartition.Segment(lo = start, loIncl = true, hi = end, hiIncl = false))

    private fun openRange(start: String): String =
        VersionRangePartition.render(VersionRangePartition.Segment(lo = start, loIncl = true, hi = null, hiIncl = false))
}
