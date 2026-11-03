package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * Pure mode-aware cross-component uniqueness matrix (no Spring). Mirrors the legacy #24/#25 invariant
 * decided deterministically from stored modes.
 */
class OwnershipCollisionMatrixTest {
    private val always: (String, String) -> Boolean = { _, _ -> true }
    private val base = "(,0),[0,)"
    private val versionRangeFactory = VersionRangeFactory(VersionNames("serviceCBranch", "serviceC", "minorC"))
    private val realIntersect: (String, String) -> Boolean = { x, y ->
        versionRangeFactory.create(x).isIntersect(versionRangeFactory.create(y))
    }

    private fun claim(
        key: String,
        group: String = "org.example",
        mode: ArtifactIdMode = ArtifactIdMode.ALL,
        tokens: Set<String> = emptySet(),
        range: String = base,
    ) = OwnershipClaim(key, range, groupTokensOf(group), mode, tokens)

    private fun collides(
        a: OwnershipClaim,
        b: OwnershipClaim,
    ) = computeOwnershipCollisions(listOf(a), listOf(b), always).isNotEmpty()

    @Test
    @DisplayName("EXPLICIT × EXPLICIT collides iff token sets intersect")
    fun explicitExplicit() {
        assertTrue(
            collides(
                claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x", "y")),
                claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("y")),
            ),
        )
        assertEquals(
            false,
            collides(
                claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x")),
                claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("z")),
            ),
        )
    }

    @Test
    @DisplayName("EXPLICIT × ALL_EXCEPT_CLAIMED never collides (catch-all yields)")
    fun explicitAllExcept() {
        assertEquals(
            false,
            collides(
                claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x")),
                claim("b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
            ),
        )
    }

    @Test
    @DisplayName("ALL_EXCEPT_CLAIMED × ALL_EXCEPT_CLAIMED collides (two fallback owners)")
    fun allExceptAllExcept() {
        assertTrue(
            collides(
                claim("a", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
                claim("b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
            ),
        )
    }

    @Test
    @DisplayName("ALL × anything collides (incl. ALL × ALL and ALL × EXPLICIT)")
    fun allVsAnything() {
        assertTrue(collides(claim("a", mode = ArtifactIdMode.ALL), claim("b", mode = ArtifactIdMode.ALL)))
        assertTrue(
            collides(
                claim("a", mode = ArtifactIdMode.ALL),
                claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x")),
            ),
        )
        assertTrue(
            collides(
                claim("a", mode = ArtifactIdMode.ALL),
                claim("b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
            ),
        )
    }

    @Test
    @DisplayName("no shared group token ⇒ no collision")
    fun disjointGroups() {
        assertEquals(
            false,
            collides(
                claim("a", group = "org.a", mode = ArtifactIdMode.ALL),
                claim("b", group = "org.b", mode = ArtifactIdMode.ALL),
            ),
        )
    }

    @Test
    @DisplayName("ALL_EXCEPT_CLAIMED × ALL_EXCEPT_CLAIMED on DISJOINT groups ⇒ no collision")
    fun allExceptAllExceptDisjointGroups() {
        assertEquals(
            false,
            collides(
                claim("a", group = "org.a", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
                claim("b", group = "org.b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
            ),
        )
    }

    @Test
    @DisplayName("non-intersecting ranges ⇒ no collision")
    fun disjointRanges() {
        val v = computeOwnershipCollisions(
            listOf(claim("a", mode = ArtifactIdMode.ALL, range = "[1,2)")),
            listOf(claim("b", mode = ArtifactIdMode.ALL, range = "[2,3)")),
            { r1, r2 -> !(r1 == "[1,2)" && r2 == "[2,3)") },
        )
        assertTrue(v.isEmpty())
    }

    @Test
    @DisplayName("same componentKey never self-collides (multi-mapping / rerun)")
    fun sameComponent() {
        assertEquals(false, collides(claim("a", mode = ArtifactIdMode.ALL), claim("a", mode = ArtifactIdMode.ALL)))
    }

    @Test
    @DisplayName("override REPLACES base (shadow skip): a base claim does not conflict in a range its own component overrides")
    fun overrideShadowsBase() {
        // Rival B: base EXPLICIT[old] (all versions) + per-range override [1,2) EXPLICIT[new].
        // New component A claims [old] only in [1,2) — legitimate, because B gave up [old] there.
        val newA = listOf(claim("A", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old"), range = "[1,2)"))
        val existingB =
            listOf(
                claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old"), range = base),
                claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("new"), range = "[1,2)"),
            )
        // ALL_VERSIONS intersects everything; [1,2) intersects only itself here.
        val intersect: (String, String) -> Boolean = { x, y -> x == base || y == base || x == y }

        // WITHOUT the shadow map the base over-claims and the matrix false-409s.
        assertTrue(computeOwnershipCollisions(newA, existingB, intersect).isNotEmpty())

        // WITH B's override range shadowing its base, no conflict (B's base [old] is replaced in [1,2)).
        assertTrue(
            computeOwnershipCollisions(newA, existingB, intersect, mapOf("B" to setOf("[1,2)"))).isEmpty(),
        )
    }

    @Test
    @DisplayName("shadow skip does NOT mask a real base-vs-override conflict in an un-overridden range")
    fun shadowDoesNotMaskRealConflict() {
        // A claims [old] in [2,3); B base owns [old] everywhere and overrides only [1,2) → still meets A in [2,3).
        val newA = listOf(claim("A", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old"), range = "[2,3)"))
        val existingB = listOf(claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old"), range = base))
        val intersect: (String, String) -> Boolean = { x, y -> x == base || y == base || x == y }
        assertTrue(
            computeOwnershipCollisions(newA, existingB, intersect, mapOf("B" to setOf("[1,2)"))).isNotEmpty(),
        )
    }

    @Test
    @DisplayName("a base claim is in force only within its component's supported versions (migrated archived w4u)")
    fun baseClaimClippedToCoverage() {
        // Archived w4u was migrated with ALL ownership of com.openwaygroup.w4u at all versions, but it is
        // supported only in its old DSL blocks. The legacy DSL validator compared each block's own range on
        // both sides, so dbadapter (supported in later versions) never met it there.
        val newDbadapter =
            listOf(
                claim("dbadapter", group = "com.openwaygroup.w4u", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("dbadapter")),
            )
        val existingW4u = listOf(claim("w4u", group = "com.openwaygroup.w4u", mode = ArtifactIdMode.ALL))

        assertTrue(computeOwnershipCollisions(newDbadapter, existingW4u, realIntersect).isNotEmpty())
        assertTrue(
            computeOwnershipCollisions(
                newDbadapter,
                existingW4u,
                realIntersect,
                coverageByComponent =
                    mapOf("w4u" to listOf("[2.4.62-0001,2.4.64-9999]"), "dbadapter" to listOf("[10,)")),
            ).isEmpty(),
        )
    }

    @Test
    @DisplayName("a per-range claim does not collide with a rival base outside the rival's supported versions (mesh-agent vs mesh-client)")
    fun perRangeClaimVsBaseOutsideRivalCoverage() {
        val newMeshAgent =
            listOf(claim("mesh-agent", group = "com.openwaygroup.mesh", mode = ArtifactIdMode.ALL, range = "[1.0.0,1.1.999)"))
        val existingMeshClient =
            listOf(
                claim("mesh-client", group = "com.openwaygroup.mesh", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("mesh-client")),
            )
        assertTrue(
            computeOwnershipCollisions(
                newMeshAgent,
                existingMeshClient,
                realIntersect,
                coverageByComponent = mapOf("mesh-client" to listOf("[1.2.0,)")),
            ).isEmpty(),
        )
    }

    @Test
    @DisplayName("build-suffixed ranges (one with a space after the comma) parse and are pairwise disjoint (archived system)")
    fun buildSuffixedRangesAreDisjoint() {
        val ranges =
            listOf("[1.0.36-0000, 1.0.37-0000)", "[1.0.37-0001,1.0.37-0004]", "[1.0.37-0005,1.0.37-0010]", "[1.0.37-0011,1.0.38-0000)")
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                assertEquals(false, realIntersect(ranges[i], ranges[j]), "${ranges[i]} vs ${ranges[j]}")
            }
        }
    }

    @Test
    @DisplayName("supported versions do not mask a real conflict inside them, on either side")
    fun coverageKeepsRealConflict() {
        val coverageB = listOf("[1.0,1.2)", "[1.4,2.0)")
        val b = listOf(claim("B", mode = ArtifactIdMode.ALL))

        val v =
            computeOwnershipCollisions(
                listOf(claim("A", mode = ArtifactIdMode.ALL, range = "[1.5,1.6)")),
                b,
                realIntersect,
                coverageByComponent = mapOf("B" to coverageB),
            )
        assertEquals(1, v.size)
        assertTrue(v.single().contains("'[1.5,1.6)' ∩ '[1.4,2.0)'"), v.single())

        // Both base with bounded coverage: they meet only where both are supported.
        val baseA = listOf(claim("A", mode = ArtifactIdMode.ALL))
        assertTrue(
            computeOwnershipCollisions(
                baseA,
                b,
                realIntersect,
                coverageByComponent = mapOf("A" to listOf("[1.2,1.4)"), "B" to coverageB),
            ).isEmpty(),
        )
        assertTrue(
            computeOwnershipCollisions(
                baseA,
                b,
                realIntersect,
                coverageByComponent = mapOf("A" to listOf("[1.1,1.3)"), "B" to coverageB),
            ).isNotEmpty(),
        )
    }
}
