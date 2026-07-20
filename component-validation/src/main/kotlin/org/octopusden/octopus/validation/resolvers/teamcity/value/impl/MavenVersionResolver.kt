package org.octopusden.octopus.validation.resolvers.teamcity.value.impl

import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * Derives a [MavenVersion] from an already reference-resolved parameter value.
 *
 * Any value containing a `maven` token (case-insensitive) is treated as a Maven version
 * reference; known version tokens are checked longest/most-specific first so bare `3` never
 * shadows `3.3.9`/`3.6.0`/`3.6.3`. Values with no `maven` marker, or no known token, resolve to
 * `null`.
 */
class MavenVersionResolver : ValueVersionResolver<MavenVersion> {
    override fun resolve(value: String): MavenVersion? {
        if (!MARKER.containsMatchIn(value)) return null
        return TOKENS.firstNotNullOfOrNull { token -> tokenRegex(token).find(value)?.let { MavenVersion(token) } }
    }

    private fun tokenRegex(token: String): Regex =
        if (token == "LATEST") Regex(Regex.escape(token)) else Regex("""(?<!\d)${Regex.escape(token)}(?!\d)""")

    private companion object {
        val MARKER = Regex("maven", RegexOption.IGNORE_CASE)

        // Longest/most-specific first: "3" would otherwise match inside "3.3.9"/"3.6.0"/"3.6.3".
        val TOKENS = listOf("3.6.3", "3.6.0", "3.3.9", "LATEST", "3")
    }
}
