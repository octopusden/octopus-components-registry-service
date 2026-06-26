package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode

/**
 * Pure mode-aware cross-component uniqueness matrix (no Spring). Mirrors the legacy #24/#25 invariant
 * decided deterministically from stored modes.
 */
class OwnershipCollisionMatrixTest {

    private val always: (String, String) -> Boolean = { _, _ -> true }
    private val base = "(,0),[0,)"

    private fun claim(
        key: String,
        group: String = "org.example",
        mode: ArtifactIdMode = ArtifactIdMode.ALL,
        tokens: Set<String> = emptySet(),
        range: String = base,
    ) = OwnershipClaim(key, range, groupTokensOf(group), mode, tokens)

    private fun collides(a: OwnershipClaim, b: OwnershipClaim) =
        computeOwnershipCollisions(listOf(a), listOf(b), always).isNotEmpty()

    @Test
    @DisplayName("EXPLICIT × EXPLICIT collides iff token sets intersect")
    fun explicitExplicit() {
        assertTrue(collides(claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x", "y")),
            claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("y"))))
        assertEquals(false, collides(claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x")),
            claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("z"))))
    }

    @Test
    @DisplayName("EXPLICIT × ALL_EXCEPT_CLAIMED never collides (catch-all yields)")
    fun explicitAllExcept() {
        assertEquals(false, collides(claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x")),
            claim("b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED)))
    }

    @Test
    @DisplayName("ALL_EXCEPT_CLAIMED × ALL_EXCEPT_CLAIMED collides (two fallback owners)")
    fun allExceptAllExcept() {
        assertTrue(collides(claim("a", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
            claim("b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED)))
    }

    @Test
    @DisplayName("ALL × anything collides (incl. ALL × ALL and ALL × EXPLICIT)")
    fun allVsAnything() {
        assertTrue(collides(claim("a", mode = ArtifactIdMode.ALL), claim("b", mode = ArtifactIdMode.ALL)))
        assertTrue(collides(claim("a", mode = ArtifactIdMode.ALL),
            claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x"))))
        assertTrue(collides(claim("a", mode = ArtifactIdMode.ALL),
            claim("b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED)))
    }

    @Test
    @DisplayName("no shared group token ⇒ no collision")
    fun disjointGroups() {
        assertEquals(false, collides(claim("a", group = "org.a", mode = ArtifactIdMode.ALL),
            claim("b", group = "org.b", mode = ArtifactIdMode.ALL)))
    }

    @Test
    @DisplayName("ALL_EXCEPT_CLAIMED × ALL_EXCEPT_CLAIMED on DISJOINT groups ⇒ no collision")
    fun allExceptAllExceptDisjointGroups() {
        assertEquals(false, collides(claim("a", group = "org.a", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED),
            claim("b", group = "org.b", mode = ArtifactIdMode.ALL_EXCEPT_CLAIMED)))
    }

    @Test
    @DisplayName("non-intersecting ranges ⇒ no collision")
    fun disjointRanges() {
        val v = computeOwnershipCollisions(
            listOf(claim("a", mode = ArtifactIdMode.ALL, range = "[1,2)")),
            listOf(claim("b", mode = ArtifactIdMode.ALL, range = "[2,3)")),
        ) { r1, r2 -> !(r1 == "[1,2)" && r2 == "[2,3)") }
        assertTrue(v.isEmpty())
    }

    @Test
    @DisplayName("same componentKey never self-collides (multi-mapping / rerun)")
    fun sameComponent() {
        assertEquals(false, collides(claim("a", mode = ArtifactIdMode.ALL), claim("a", mode = ArtifactIdMode.ALL)))
    }
}
