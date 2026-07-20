package org.octopusden.octopus.validation.resolvers.teamcity.value.impl

import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * Derives a [JavaVersion] from an already reference-resolved parameter value.
 *
 * Detection no longer depends on a specific env-var convention (`BUILD_ENV` vs `env.JDK`) --
 * real TeamCity configs use a growing, vendor-specific set of conventions
 * (`env.JDK_ORACLE_17_x64`, `env.JDK_ZULU_1_8_x64`, `env.OPENJDK_21`, `env.JDK_RH_21_x64`, ...)
 * that a marker allowlist can't keep up with. Instead, any value containing a `jdk`, `java`, or
 * `jvm` token (case-insensitive) is treated as a Java version reference, and the known version
 * tokens below are matched against the whole value.
 *
 * This also covers values that are already-resolved directory paths rather than the env-var name
 * itself -- e.g. `env.JDK_21_0` might arrive here as `/usr/lib/jvm/java-21-openjdk-21.0.11.0.10-2.el9.x86_64`
 * (Linux) or `C:\Java\RedHat\21` (Windows). Both the reference-style value and the resolved
 * directory-style value contain a `jdk`/`java`/`jvm` marker and a recognizable version token, so
 * both resolve the same way without needing separate handling.
 *
 * Matching picks the version token that starts *earliest* in the value, not the first one found
 * by token priority. This matters once a real path is in play: `java-21-openjdk-21.0.11.0.10-...`
 * contains both `21` (the major version, appearing first) and `11` (an update/build number,
 * appearing later) as valid digit-bounded tokens. Real JDK paths consistently put the major
 * version first, so leftmost-match reliably picks `21` over the coincidental `11`. Ties (which
 * shouldn't occur given the token set below) prefer the longer, more specific token.
 *
 * `18` is a known legacy alias for Java `1.8` (seen as `env.JDK_18`) -- NOT Java 18. If Java 18 is
 * ever introduced under a plain `18` token this will need revisiting; for now the known legacy
 * convention wins.
 *
 * Values with no java-ish marker, or no known version token within it, resolve to `null`.
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
