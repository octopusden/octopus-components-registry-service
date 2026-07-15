package org.octopusden.octopus.components.registry.server.util

/**
 * Distribution-GAV collision identity shared by the v4 write-path
 * cross-component validation (`ComponentManagementServiceImpl`) and the
 * migration pre-pass (`ImportServiceImpl`), so API saves and DSL→DB migration
 * enforce the SAME uniqueness rule.
 *
 * Identity is the FULL coordinate `(group, artifact, extension, classifier)`:
 * `g:a:zip` and `g:a:apk` are DIFFERENT artifacts and must not conflict.
 * Group/artifact match under the union of the two legacy rules — #25 pattern
 * containment ([patternsOverlap]: whole-group-vs-CSV-items + `*`/regex artifact
 * matching, `MavenArtifactMatcher` parity) and #24 exact token-pair sharing
 * ([exactTokenPairShared]). Extension and classifier are plain values compared
 * with null-safe equality, where null only matches null (an absent extension is
 * its own identity, not a wildcard — `g:a` does not collide with `g:a:zip`).
 */
internal object MavenGavCollision {
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
     * Legacy `MavenArtifactMatcher.groupIdMatches` parity — and the asymmetry is
     * LOAD-BEARING: the FIRST argument is this row's group value compared as ONE
     * WHOLE string against the rival pattern's comma-split items. A CSV group
     * therefore never matches another CSV's items, only an identical whole string
     * or a single group listed in the rival's CSV. The prod DSL is authored
     * against exactly this contract: the production model/api component families share
     * CSV group elements (with the wildcard `ANY_ARTIFACT` vs literal placeholder
     * artifacts) and are LEGAL — the daily legacy validation passes them.
     * Splitting BOTH sides (the previous behaviour) was stricter than legacy and
     * falsely flagged that long-standing data, bricking the migration gate.
     * `|` is NOT a separator (legacy splits by ',' only); trimming items is the
     * one deliberate liberalization (prod CSVs carry no spaces).
     * [patternsOverlap] calls both directions, so callers of that entry point
     * need not care — but do not swap the arguments here.
     */
    fun groupIdMatches(
        groupId: String,
        groupIdPattern: String,
    ): Boolean {
        val candidate = groupId.trim()
        if (candidate.isEmpty()) return false
        return groupIdPattern.split(',').any { it.trim() == candidate }
    }

    /** Legacy `EscrowConfigValidator.SPLIT_PATTERN` for artifact tokens. */
    private val ARTIFACT_TOKEN_SPLIT = Regex("[,|\\s]+")

    /**
     * Legacy rule #24 (`validateVersionConflicts`) parity: two rows collide when they
     * share an EXACT `(groupItem, artifactToken)` pair — groups split by ',' and
     * artifact patterns by the legacy `[,|\s]+`, tokens compared literally (no regex).
     * This is what catches identical-CSV duplicates that the #25 whole-string
     * containment deliberately ignores: `groupPattern "x,y"` on two components with
     * the same artifact IS a legacy violation via the shared `x` (and `y`) items,
     * even though `"x,y"` never matches the items of another `"x,y"`. The prod
     * model/api families stay legal — their artifact TOKENS differ
     * (literal placeholders vs the `ANY_ARTIFACT` regex source text).
     * Trim is the same deliberate deviation as in [groupIdMatches].
     */
    fun exactTokenPairShared(
        group1: String,
        artifact1: String,
        group2: String,
        artifact2: String,
    ): Boolean {
        val groups1 = group1
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (groups1.isEmpty()) return false
        if (group2.split(',').map { it.trim() }.none { it in groups1 }) return false
        val artifacts1 = artifact1
            .split(ARTIFACT_TOKEN_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (artifacts1.isEmpty()) return false
        return artifact2.split(ARTIFACT_TOKEN_SPLIT).map { it.trim() }.any { it.isNotEmpty() && it in artifacts1 }
    }

    fun artifactIdMatches(
        artifactId: String,
        artifactPattern: String,
    ): Boolean {
        if (artifactPattern == "*") return true
        val cached = compiledArtifactPatterns.computeIfAbsent(artifactPattern) { pattern ->
            runCatching { Regex(pattern.replace(",", "|")) }.getOrNull() ?: INVALID_PATTERN
        }
        return (cached as? Regex)?.matches(artifactId) ?: false
    }

    private fun patternContainsAnother(
        group1: String,
        artifact1: String,
        group2: String,
        artifact2: String,
    ): Boolean = groupIdMatches(group1, group2) && artifactIdMatches(artifact1, artifact2)

    fun patternsOverlap(
        group1: String,
        artifact1: String,
        group2: String,
        artifact2: String,
    ): Boolean =
        patternContainsAnother(group1, artifact1, group2, artifact2) ||
            patternContainsAnother(group2, artifact2, group1, artifact1)

    /**
     * True when two rows claim the same artifact identity: equal extension AND
     * equal classifier (cheap checks first) AND a group/artifact match under
     * EITHER legacy rule — #25 pattern containment ([patternsOverlap]) or #24
     * exact token-pair sharing ([exactTokenPairShared]). Version-range
     * intersection is the caller's concern.
     */
    @Suppress("LongParameterList")
    fun identityCollides(
        group1: String,
        artifact1: String,
        extension1: String?,
        classifier1: String?,
        group2: String,
        artifact2: String,
        extension2: String?,
        classifier2: String?,
    ): Boolean =
        extension1 == extension2 &&
            classifier1 == classifier2 &&
            (
                patternsOverlap(group1, artifact1, group2, artifact2) ||
                    exactTokenPairShared(group1, artifact1, group2, artifact2)
            )

    /**
     * Human-readable ORIGIN of a `distribution_maven_artifacts` row for conflict
     * messages: rows on a `group-artifact-pattern` MARKER are synthesized from the
     * component-level `groupId`/`artifactId` mapping (MIG-047) — calling those
     * "distribution GAV" misled operators into hunting for a `distribution{}`
     * block that does not exist in the DSL.
     */
    fun originLabel(overriddenAttribute: String?): String =
        if (overriddenAttribute == "group-artifact-pattern") {
            "groupId/artifactId mapping"
        } else {
            "distribution GAV"
        }

    /** Human-readable `g:a[:e[:c]]` label; an empty extension slot is kept when only a classifier is present. */
    fun gavLabel(
        group: String,
        artifact: String,
        extension: String?,
        classifier: String?,
    ): String =
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
