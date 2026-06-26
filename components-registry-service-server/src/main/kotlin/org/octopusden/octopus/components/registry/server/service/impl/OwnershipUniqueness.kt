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
 * whose range C overrides (exact-range match — the realistic case, since an ownership override range
 * equals an existing config range by invariant). This removes the false 409 where one component's
 * per-range override frees a token that another component legitimately claims only in that range —
 * symmetrically on both sides. It is SOUND: a base is skipped only where it is provably replaced; a
 * strict-subset override range (different string) is not skipped, which can only over-report, never miss.
 */
internal fun computeOwnershipCollisions(
    newClaims: List<OwnershipClaim>,
    existingClaims: List<OwnershipClaim>,
    rangesIntersect: (String, String) -> Boolean,
    shadowRangesByComponent: Map<String, Set<String>> = emptyMap(),
): List<String> {
    val violations = mutableListOf<String>()

    fun shadowed(claim: OwnershipClaim, otherRange: String): Boolean =
        claim.versionRange == OWNERSHIP_ALL_VERSIONS &&
            otherRange in shadowRangesByComponent[claim.componentKey].orEmpty()

    fun check(a: OwnershipClaim, b: OwnershipClaim) {
        if (a.componentKey == b.componentKey) return
        val sharedGroup = a.groupTokens.firstOrNull { it in b.groupTokens } ?: return
        if (!rangesIntersect(a.versionRange, b.versionRange)) return
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
                "(${b.mode}) in intersecting version ranges '${a.versionRange}' ∩ '${b.versionRange}'"
    }

    for (i in newClaims.indices) {
        for (j in i + 1 until newClaims.size) check(newClaims[i], newClaims[j])
        for (existing in existingClaims) check(newClaims[i], existing)
    }
    return violations
}

/** Split a comma group-list into exact-match group tokens (mirrors `MavenArtifactMatcher.groupIdMatches`). */
internal fun groupTokensOf(groupPattern: String): Set<String> =
    groupPattern.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
