package org.octopusden.octopus.components.registry.server.util

/** Compares Java version strings by major version — `"1.8"`, `"8"`, and `"17.0.9"` all read as major version 8/8/17. */
object JavaVersionComparator {
    private val ONE_DOT = Regex("""^1\.(\d+)(\..*)?$""")
    private val PLAIN = Regex("""^(\d+)(\..*)?$""")

    fun majorVersion(raw: String): Int? {
        val trimmed = raw.trim()
        return ONE_DOT.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            ?: PLAIN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** An unparseable value is never equal to anything. */
    fun valuesEqual(
        a: String,
        b: String,
    ): Boolean {
        val majorA = majorVersion(a) ?: return false
        val majorB = majorVersion(b) ?: return false
        return majorA == majorB
    }
}
