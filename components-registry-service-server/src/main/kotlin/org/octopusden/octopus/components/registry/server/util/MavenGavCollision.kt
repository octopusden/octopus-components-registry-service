package org.octopusden.octopus.components.registry.server.util

/**
 * Distribution-GAV collision identity shared by the v4 write-path
 * cross-component validation (`ComponentManagementServiceImpl`) and the
 * migration pre-pass (`ImportServiceImpl`), so API saves and DSL→DB migration
 * enforce the SAME uniqueness rule.
 *
 * Identity is the FULL coordinate `(group, artifact, extension, classifier)`:
 * `g:a:zip` and `g:a:apk` are DIFFERENT artifacts and must not conflict.
 * Group/artifact keep the legacy pattern semantics (CSV/pipe-separated groups,
 * `*` / regex / CSV artifact patterns — `MavenArtifactMatcher` parity);
 * extension and classifier are plain values compared with null-safe equality,
 * where null only matches null (an absent extension is its own identity, not a
 * wildcard — `g:a` does not collide with `g:a:zip`).
 */
internal object MavenGavCollision {
    private val GROUP_ID_SPLIT = Regex("[,|]")

    /**
     * Compiled-artifact-pattern cache: the migration pre-pass calls
     * [artifactIdMatches] inside an O(new×(new+existing)) pairwise loop, and the
     * pattern population is tiny (one per distribution row) — without the cache
     * each pair recompiles the regex. Unbounded by design: keys come from the
     * finite DSL/DB pattern set, not user input.
     */
    private val compiledArtifactPatterns = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Cache sentinel for unparseable patterns (ConcurrentHashMap cannot hold null values). */
    private val INVALID_PATTERN = Any()

    /**
     * NOTE the asymmetry (legacy `MavenArtifactMatcher` parity): the FIRST argument
     * is this row's (possibly CSV/pipe) group value whose elements form the candidate
     * set; the SECOND is the rival's pattern whose elements are tested for membership
     * in that set. [patternsOverlap] calls it in both directions, so callers of that
     * entry point need not care — but do not swap the arguments here.
     */
    fun groupIdMatches(groupId: String, groupIdPattern: String): Boolean {
        val groupIds = groupId.split(GROUP_ID_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return groupIdPattern.split(GROUP_ID_SPLIT).map { it.trim() }.any { it in groupIds }
    }

    fun artifactIdMatches(artifactId: String, artifactPattern: String): Boolean {
        if (artifactPattern == "*") return true
        val cached = compiledArtifactPatterns.computeIfAbsent(artifactPattern) { pattern ->
            runCatching { Regex(pattern.replace(",", "|")) }.getOrNull() ?: INVALID_PATTERN
        }
        return (cached as? Regex)?.matches(artifactId) ?: false
    }

    private fun patternContainsAnother(
        group1: String, artifact1: String,
        group2: String, artifact2: String,
    ): Boolean = groupIdMatches(group1, group2) && artifactIdMatches(artifact1, artifact2)

    fun patternsOverlap(
        group1: String, artifact1: String,
        group2: String, artifact2: String,
    ): Boolean = patternContainsAnother(group1, artifact1, group2, artifact2) ||
        patternContainsAnother(group2, artifact2, group1, artifact1)

    /**
     * True when two rows claim the same artifact identity: equal extension AND
     * equal classifier (cheap checks first) AND overlapping group/artifact
     * patterns. Version-range intersection is the caller's concern.
     */
    @Suppress("LongParameterList")
    fun identityCollides(
        group1: String, artifact1: String, extension1: String?, classifier1: String?,
        group2: String, artifact2: String, extension2: String?, classifier2: String?,
    ): Boolean = extension1 == extension2 &&
        classifier1 == classifier2 &&
        patternsOverlap(group1, artifact1, group2, artifact2)

    /** Human-readable `g:a[:e[:c]]` label; an empty extension slot is kept when only a classifier is present. */
    fun gavLabel(group: String, artifact: String, extension: String?, classifier: String?): String =
        buildString {
            append(group).append(':').append(artifact)
            if (extension != null || classifier != null) {
                append(':').append(extension ?: "")
            }
            if (classifier != null) {
                append(':').append(classifier)
            }
        }
}
