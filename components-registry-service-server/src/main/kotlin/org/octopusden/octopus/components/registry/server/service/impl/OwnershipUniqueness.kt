package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

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
 * NOTE (effective-per-range, override REPLACES base): the SELF side (the component under validation) is
 * passed as effective-per-range claims (`ComponentManagementServiceImpl.effectiveOwnClaims`), so a base
 * mapping is not claimed in a sub-range one of its own overrides shadows — no false 409 for the realistic
 * authoring flow (the component being edited owns the override). The RIVAL side is modelled from raw rows
 * (base at ALL_VERSIONS), which is SOUND — it never misses a real conflict; it can only over-report in the
 * doubly-rare symmetric case where a *rival's* per-range override changes the owned tokens AND the edited
 * component claims the rival's base token only within that rival override's range. Production has no such
 * shape (the one prod regex is the single `(?!X)` lookahead). Range-restricting rival base claims by each
 * rival's own override ranges (needs the rivals' configuration ranges) is the tracked follow-up.
 */
internal fun computeOwnershipCollisions(
    newClaims: List<OwnershipClaim>,
    existingClaims: List<OwnershipClaim>,
    rangesIntersect: (String, String) -> Boolean,
): List<String> {
    val violations = mutableListOf<String>()

    fun check(a: OwnershipClaim, b: OwnershipClaim) {
        if (a.componentKey == b.componentKey) return
        val sharedGroup = a.groupTokens.firstOrNull { it in b.groupTokens } ?: return
        if (!rangesIntersect(a.versionRange, b.versionRange)) return
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
