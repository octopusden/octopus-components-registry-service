package org.octopusden.octopus.components.registry.server.util

/** Compares Maven version strings, with `"LATEST"` as its own token — never equal to a numbered version, and always the greatest. */
object MavenVersionComparator {
    private const val LATEST_VERSION = "LATEST"

    fun valuesEqual(
        a: String,
        b: String,
    ): Boolean {
        if (a == LATEST_VERSION || b == LATEST_VERSION) return a == b
        return try {
            VersionRangePartition.defaultVersionCompare(a, b) == 0
        } catch (_: Exception) {
            false
        }
    }

    fun compare(
        a: String,
        b: String,
    ): Int =
        when {
            a == LATEST_VERSION && b == LATEST_VERSION -> 0
            a == LATEST_VERSION -> 1
            b == LATEST_VERSION -> -1
            else -> VersionRangePartition.defaultVersionCompare(a, b)
        }
}
