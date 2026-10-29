package org.octopusden.octopus.validation.resolvers.teamcity.value.impl

import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * Derives a [JavaVersion] from an already reference-resolved parameter value.
 *
 * Any value containing a `jdk`, `java`, or `jvm` token (case-insensitive) is treated as a Java
 * version reference — this covers both env-var-style names (`env.JDK_ORACLE_17_x64`) and already
 * resolved directory paths (`/usr/lib/jvm/java-21-openjdk-21.0.11.0.10-2.el9.x86_64`). The known
 * version tokens below are matched against the whole value, picking the token that starts
 * *earliest* (so a path like `java-21-openjdk-21.0.11...` picks `21`, not the later `11`).
 *
 * `18` is a known legacy alias for Java `1.8` (e.g. `env.JDK_18`), NOT Java 18. Values with no
 * java-ish marker, or no known version token, resolve to `null`.
 */
class JavaVersionResolver : ValueVersionResolver<JavaVersion> {
    override fun resolve(value: String): JavaVersion? {
        if (!MARKER.containsMatchIn(value)) return null
        return TOKENS
            .mapNotNull { (token, version) -> tokenRegex(token).find(value)?.let { Triple(it.range.first, -token.length, version) } }
            .minWithOrNull(compareBy({ it.first }, { it.second }))
            ?.let { JavaVersion(it.third) }
    }

    private fun tokenRegex(token: String): Regex = Regex("""(?<!\d)${Regex.escape(token)}(?!\d)""")

    private companion object {
        val MARKER = Regex("jdk|java|jvm", RegexOption.IGNORE_CASE)

        val TOKENS = listOf(
            "1_8" to "1.8",
            "1.8" to "1.8",
            "18" to "1.8", // legacy alias, e.g. env.JDK_18 -> Java 8, NOT Java 18
            "11" to "11",
            "17" to "17",
            "21" to "21",
            "25" to "25",
            "8" to "8",
        )
    }
}
