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

    fun groupIdMatches(groupId: String, groupIdPattern: String): Boolean {
        val groupIds = groupId.split(GROUP_ID_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return groupIdPattern.split(GROUP_ID_SPLIT).map { it.trim() }.any { it in groupIds }
    }

    fun artifactIdMatches(artifactId: String, artifactPattern: String): Boolean {
        return artifactPattern == "*" || runCatching {
            val regexPattern = artifactPattern.replace(",", "|")
            Regex(regexPattern).matches(artifactId)
        }.getOrDefault(false)
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
