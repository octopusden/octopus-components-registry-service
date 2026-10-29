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

        // MAVEN and the version must both be whole path elements: "apache-MAVEN/3.10" or "MAVEN/3.6.3-foo" is not a
        // version segment. MAVEN may follow only the start, a path separator, "=" (VAR=/path), whitespace or a quote.
        val PATH_SEGMENT = Regex("""(?<![^/\\=\s"'])MAVEN[/\\](LATEST|\d+(?:\.\d+)*)(?=$|[/\\\s"';])""")

        // Longest/most-specific first: "3" would otherwise match inside "3.3.9"/"3.6.0"/"3.6.3".
        val TOKENS = listOf("3.6.3", "3.6.0", "3.3.9", "LATEST", "3")
    }
}
