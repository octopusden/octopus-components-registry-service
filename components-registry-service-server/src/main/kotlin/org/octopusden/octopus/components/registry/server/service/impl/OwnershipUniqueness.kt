package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

/** Base ownership/version range; must match EscrowConfigurationLoader.ALL_VERSIONS. */
internal const val OWNERSHIP_ALL_VERSIONS = "(,0),[0,)"

/**
 * One artifact-ownership claim for the cross-component uniqueness matrix: a component's mapping at a
 * version range, its group tokens (comma-split, exact match), its [mode], and (EXPLICIT) literal tokens.
 */
internal data class OwnershipClaim(
    val componentKey: String,
    val versionRange: String,
    val groupTokens: Set<String>,
    val mode: ArtifactIdMode,
    val tokens: Set<String>,
)

/**
 * Mode-aware cross-component ownership uniqueness (restores legacy #24/#25 for the groupId/artifactId
 * ownership mapping, now decided deterministically from stored modes — no probe/regex heuristics).
 *
 * For each pair of claims from DIFFERENT components that share ≥1 group token in intersecting ranges:
 *  - EXPLICIT × EXPLICIT      → conflict iff their literal token sets intersect.
 *  - EXPLICIT × ALL_EXCEPT    → no conflict (ALL_EXCEPT yields to the explicit claim).
 *  - ALL_EXCEPT × ALL_EXCEPT  → conflict (two fallback owners of the same group/range).
 *  - ALL × anything           → conflict (ALL claims everything in the group/range).
 *
 * Only new-vs-new and new-vs-existing pairs are reported (existing-vs-existing is pre-existing DB state);
 * same-componentKey pairs never collide (multi-range/multi-group components, idempotent reruns). Pure —
 * unit-tested without Spring.
 *
 * NOTE (override REPLACES base — shadow skip): a base (`ALL_VERSIONS`) mapping is NOT in force in any
 * range the SAME component overrides, so it cannot conflict there. [shadowRangesByComponent] gives each
 * component's own override range strings; a base claim of component C is skipped against a rival claim
 * whose range string equals one C overrides. Replacement is per component, not per group: the resolver
 * takes a range's override mappings instead of ALL base mappings. Exact string match alone misses most
 * shapes (a coverage segment or a sub-range never equals the override range), so the subtraction below
 * is the main mechanism; this skip is the fallback when a range cannot be parsed. Both are SOUND: a base
 * is skipped only where it is provably replaced; anything not proven is kept, which can only over-report.
 *
 * NOTE (base is in force only where the component is supported): the base mapping is stored at
 * `ALL_VERSIONS` (ADR-018), but a component with bounded coverage (`RANGE_PRESENCE` rows) defines no
 * version outside it, so it owns nothing there. [coverageByComponent] gives each component's bounded
 * supported ranges (absent / empty ⇒ supported = ALL); a base claim is matched against those ranges
 * instead of `ALL_VERSIONS`. Without this, a migrated component whose every DSL block declared the same
 * groupId (stored once at `ALL_VERSIONS`) blocks every other owner of that group in every version.
 *
 * NOTE (base minus own overrides): with [subtractRanges] given, a base claim is also limited to its
 * coverage MINUS the component's own override ranges, so it is not in force where an override replaces
 * it even when the rival's range string differs from the override's (a coverage segment or a sub-range
 * never equals it). A `null` result (an unparseable range) keeps the unsubtracted ranges, which can only
 * over-report. The exact-string shadow skip above stays as the fallback for that case.
 */
internal fun computeOwnershipCollisions(
    newClaims: List<OwnershipClaim>,
    existingClaims: List<OwnershipClaim>,
    rangesIntersect: (String, String) -> Boolean,
    shadowRangesByComponent: Map<String, Set<String>> = emptyMap(),
    coverageByComponent: Map<String, List<String>> = emptyMap(),
    subtractRanges: ((List<String>, Collection<String>) -> List<String>?)? = null,
): List<String> {
    val violations = mutableListOf<String>()

    fun shadowed(
        claim: OwnershipClaim,
        otherRange: String,
    ): Boolean =
        claim.versionRange == OWNERSHIP_ALL_VERSIONS &&
            otherRange in shadowRangesByComponent[claim.componentKey].orEmpty()

    fun effectiveRanges(claim: OwnershipClaim): List<String> =
        claimRangesInForce(claim, coverageByComponent, shadowRangesByComponent, subtractRanges)

    fun check(
        a: OwnershipClaim,
        b: OwnershipClaim,
    ) {
        if (a.componentKey == b.componentKey) return
        val sharedGroup = a.groupTokens.firstOrNull { it in b.groupTokens } ?: return
        val (rangeA, rangeB) =
            effectiveRanges(a)
                .asSequence()
                .flatMap { ra -> effectiveRanges(b).asSequence().map { rb -> ra to rb } }
                .firstOrNull { (ra, rb) -> rangesIntersect(ra, rb) }
                ?: return
        // A base claim is shadowed (replaced) in any range its own component overrides → not in force there.
        if (shadowed(a, b.versionRange) || shadowed(b, a.versionRange)) return
        val conflict =
            when {
                a.mode == ArtifactIdMode.ALL || b.mode == ArtifactIdMode.ALL -> true
                a.mode == ArtifactIdMode.ALL_EXCEPT_CLAIMED && b.mode == ArtifactIdMode.ALL_EXCEPT_CLAIMED -> true
                a.mode == ArtifactIdMode.EXPLICIT && b.mode == ArtifactIdMode.EXPLICIT ->
                    a.tokens.any { it in b.tokens }
                else -> false // EXPLICIT × ALL_EXCEPT_CLAIMED — the catch-all yields
            }
        if (!conflict) return
        violations +=
            "uniqueness violation: groupId/artifactId ownership of component '${a.componentKey}' " +
            "(group '$sharedGroup', ${a.mode}) duplicates component '${b.componentKey}' " +
            "(${b.mode}) in intersecting version ranges '$rangeA' ∩ '$rangeB'"
    }

    for (i in newClaims.indices) {
        for (j in i + 1 until newClaims.size) check(newClaims[i], newClaims[j])
        for (existing in existingClaims) check(newClaims[i], existing)
    }
    return violations
}

/**
 * The ranges where [claim] is in force: a base claim is limited to its component's coverage (if bounded)
 * minus the component's own override ranges (if [subtractRanges] is given and can parse them); any other
 * claim keeps its own range.
 */
private fun claimRangesInForce(
    claim: OwnershipClaim,
    coverageByComponent: Map<String, List<String>>,
    shadowRangesByComponent: Map<String, Set<String>>,
    subtractRanges: ((List<String>, Collection<String>) -> List<String>?)?,
): List<String> {
    if (claim.versionRange != OWNERSHIP_ALL_VERSIONS) return listOf(claim.versionRange)
    val inForce = coverageByComponent[claim.componentKey]?.takeIf { it.isNotEmpty() } ?: listOf(OWNERSHIP_ALL_VERSIONS)
    val ownOverrides = shadowRangesByComponent[claim.componentKey].orEmpty()
    if (subtractRanges == null || ownOverrides.isEmpty()) return inForce
    return subtractRanges(inForce, ownOverrides) ?: inForce
}

/** Split a comma group-list into exact-match group tokens (mirrors `MavenArtifactMatcher.groupIdMatches`). */
internal fun groupTokensOf(groupPattern: String): Set<String> =
    groupPattern
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
