package org.octopusden.octopus.components.registry.server.util

/** Compares Maven version strings, with `"LATEST"` as its own token — never equal to a numbered version, and always the greatest. */
object MavenVersionComparator {
    fun valuesEqual(
        a: String,
        b: String,
    ): Boolean {
        if (a == "LATEST" || b == "LATEST") return a == b
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
            a == "LATEST" && b == "LATEST" -> 0
            a == "LATEST" -> 1
            b == "LATEST" -> -1
            else -> VersionRangePartition.defaultVersionCompare(a, b)
        }
}
