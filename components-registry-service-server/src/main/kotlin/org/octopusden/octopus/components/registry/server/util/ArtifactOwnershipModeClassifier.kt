package org.octopusden.octopus.components.registry.server.util

import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

/**
 * Classifies a legacy DSL `artifactId` pattern into an [ArtifactIdMode] for migration.
 *
 * STRICT and ORDERED — deliberately NOT the broad probe-based
 * `DatabaseComponentRegistryResolver.isCatchAllArtifactPattern` (which would misclassify
 * regexes like `[a-z]+` / `.*foo.*` / even a negative-lookahead as catch-all). The probe
 * stays resolver-only for runtime specificity; migration classification is exact:
 *
 *  1. pattern contains `(?!` (negative-lookahead exclusion) → [ArtifactIdMode.ALL_EXCEPT_CLAIMED].
 *  2. else pattern EXACTLY equals a known catch-all form → [ArtifactIdMode.ALL].
 *  3. else every token (split on `,`/`|`) passes the literal allowlist → [ArtifactIdMode.EXPLICIT].
 *  4. else (any other regex) → hard-fail ([UnclassifiableArtifactPatternException]); no escape hatch.
 *
 * The catch-all default `ANY_ARTIFACT` resolves to one of the [KNOWN_CATCH_ALL] regex forms by
 * the time the importer sees it; both the array literal and the resolved forms are accepted.
 */
object ArtifactOwnershipModeClassifier {

    /** Exact catch-all forms that map to [ArtifactIdMode.ALL] (the inherited `ANY_ARTIFACT` default). */
    private val KNOWN_CATCH_ALL = setOf("*", ".*", "[\\w-\\.]+", "[\\w-]+", "\\w+")

    /** Literal artifact/group token allowlist — letters, digits, `.`, `_`, `-` (no regex operators). */
    private val LITERAL_TOKEN = Regex("^[A-Za-z0-9_.-]+$")

    /** Split an enumeration on comma OR pipe (both are legacy separators), trimmed, non-empty. */
    fun splitTokens(pattern: String): List<String> =
        pattern.split(',', '|').map { it.trim() }.filter { it.isNotEmpty() }

    fun isLiteralToken(token: String): Boolean = LITERAL_TOKEN.matches(token)

    /**
     * @return the classified mode. For EXPLICIT the caller reads [splitTokens]; for ALL/ALL_EXCEPT
     *   there are no tokens.
     * @throws UnclassifiableArtifactPatternException for any pattern that is neither a known
     *   catch-all, a `(?!…)` exclusion, nor a pure literal enumeration.
     */
    fun classify(artifactIdPattern: String?): ArtifactIdMode {
        val pattern = artifactIdPattern?.trim().orEmpty()
        if (pattern.isEmpty()) return ArtifactIdMode.ALL // no explicit artifactId ⇒ inherited catch-all default
        if (pattern.contains("(?!")) return ArtifactIdMode.ALL_EXCEPT_CLAIMED
        if (pattern in KNOWN_CATCH_ALL) return ArtifactIdMode.ALL
        val tokens = splitTokens(pattern)
        if (tokens.isNotEmpty() && tokens.all { isLiteralToken(it) }) return ArtifactIdMode.EXPLICIT
        throw UnclassifiableArtifactPatternException(pattern)
    }
}

/**
 * Migration hard-fail: an artifactId pattern is neither a known catch-all, a `(?!…)` exclusion,
 * nor a literal enumeration. There is no raw-regex storage; a human must express it via the modes.
 */
class UnclassifiableArtifactPatternException(pattern: String) :
    IllegalStateException(
        "Cannot classify artifactId pattern '$pattern' into an ownership mode " +
            "(EXPLICIT / ALL_EXCEPT_CLAIMED / ALL). Express it via the modes or fix the DSL.",
    )
