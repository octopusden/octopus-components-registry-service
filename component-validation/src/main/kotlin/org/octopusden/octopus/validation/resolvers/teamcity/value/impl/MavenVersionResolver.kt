package org.octopusden.octopus.validation.resolvers.teamcity.value.impl

import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * Derives a [MavenVersion] from an already reference-resolved parameter value.
 *
 * A `MAVEN/<version>` path segment (`/` or `\`, as in `BUILD_ENV/MAVEN/3.9`) names the Maven
 * installation directly, so its whole version is taken — `3.6`, `3.9` and `3.10` stay distinct
 * instead of collapsing to `3`. Without such a segment, any value containing a `maven` token
 * (case-insensitive) is treated as a Maven version reference; known version tokens are checked
 * longest/most-specific first so bare `3` never shadows `3.3.9`/`3.6.0`/`3.6.3`. Values with no
 * `maven` marker, or no known token, resolve to `null`.
 */
class MavenVersionResolver : ValueVersionResolver<MavenVersion> {
    override fun resolve(value: String): MavenVersion? {
        PATH_SEGMENT.find(value)?.let { return MavenVersion(it.groupValues[1]) }
        if (!MARKER.containsMatchIn(value)) return null
        return TOKENS.firstNotNullOfOrNull { token -> tokenRegex(token).find(value)?.let { MavenVersion(token) } }
    }

    private fun tokenRegex(token: String): Regex =
        if (token == "LATEST") Regex(Regex.escape(token)) else Regex("""(?<!\d)${Regex.escape(token)}(?!\d)""")

    private companion object {
        val MARKER = Regex("maven", RegexOption.IGNORE_CASE)

        // The version must be the whole directory name: "MAVEN/3.6.3-foo" or "MAVEN/3_9" is not a version segment.
        val PATH_SEGMENT = Regex("""(?<![A-Za-z0-9_])MAVEN[/\\](LATEST|\d+(?:\.\d+)*)(?=$|[/\\\s"';])""")

        // Longest/most-specific first: "3" would otherwise match inside "3.3.9"/"3.6.0"/"3.6.3".
        val TOKENS = listOf("3.6.3", "3.6.0", "3.3.9", "LATEST", "3")
    }
}
