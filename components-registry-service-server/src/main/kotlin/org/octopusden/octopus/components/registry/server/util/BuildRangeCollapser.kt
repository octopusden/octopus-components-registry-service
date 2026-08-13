package org.octopusden.octopus.components.registry.server.util

/**
 * Collapses a build history into ranges: one run per stretch of consecutive builds carrying an
 * equal (normalized) value. A null or differing value ends the run before it. Only the run
 * containing the single highest-version build stays open-ended, and only if that build's value
 * is non-null.
 */
object BuildRangeCollapser {
    data class Build(
        val version: String,
        val value: String?,
    )

    data class Run(
        val versionRange: String,
        val value: String,
    )

    /**
     * @param builds MUST already be in ascending real version order (RMS's `descending=false`
     *   guarantee) — this does not sort them.
     * @param valuesEqual value equality after normalization; defaults to plain string equality.
     */
    fun collapse(
        builds: List<Build>,
        valuesEqual: (String, String) -> Boolean = String::equals,
    ): List<Run> {
        val runs = mutableListOf<Run>()
        var runStartVersion: String? = null
        var runValue: String? = null

        for (build in builds) {
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

        return runs
    }

    private fun closedRange(
        start: String,
        end: String,
    ): String = VersionRangePartition.render(VersionRangePartition.Segment(lo = start, loIncl = true, hi = end, hiIncl = false))

    private fun openRange(start: String): String =
        VersionRangePartition.render(VersionRangePartition.Segment(lo = start, loIncl = true, hi = null, hiIncl = false))
}
