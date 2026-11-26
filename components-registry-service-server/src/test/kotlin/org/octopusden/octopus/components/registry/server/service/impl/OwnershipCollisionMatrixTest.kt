package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.util.VersionRangePartition
import org.octopusden.releng.versions.NumericVersionFactory
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
    private val numericVersionFactory = NumericVersionFactory(VersionNames("serviceCBranch", "serviceC", "minorC"))
    private val numericCompare: (String, String) -> Int = { a, b ->
        numericVersionFactory.create(a).compareTo(numericVersionFactory.create(b))
    }
    private val subtract: (List<String>, Collection<String>) -> List<String>? = { ranges, minus ->
        VersionRangePartition.subtract(ranges, minus, numericCompare)
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
    @DisplayName("message: ALL vs EXPLICIT says what each side owns and how to fix it")
    fun messageAllVsExplicit() {
        val v =
            computeOwnershipCollisions(
                listOf(claim("acme-promotion", group = "org.example.acme", mode = ArtifactIdMode.ALL)),
                listOf(
                    claim(
                        "acme-dlgen",
                        group = "org.example.acme",
                        mode = ArtifactIdMode.EXPLICIT,
                        tokens = setOf("acme-dlgen"),
                    ),
                ),
                always,
            )
        assertEquals(
            listOf(
                "uniqueness violation: groupId/artifactId ownership conflict. " +
                    "Component 'acme-promotion' claims all artifacts of group 'org.example.acme', " +
                    "but component 'acme-dlgen' already owns artifacts 'org.example.acme:acme-dlgen' " +
                    "for all versions. List the explicit artifactIds that 'acme-promotion' publishes " +
                    "instead of all artifacts of the group.",
            ),
            v,
        )
    }

    @Test
    @DisplayName("message: EXPLICIT vs EXPLICIT names the shared artifactIds and the ranges")
    fun messageExplicitVsExplicit() {
        val v =
            computeOwnershipCollisions(
                listOf(claim("a", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("x", "y"), range = "[1,2)")),
                listOf(claim("b", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("y"))),
                always,
            )
        assertEquals(
            listOf(
                "uniqueness violation: groupId/artifactId ownership conflict. " +
                    "Component 'a' claims artifacts 'org.example:x', 'org.example:y', " +
                    "but component 'b' already owns artifacts 'org.example:y' " +
                    "for versions [1,2) vs all versions (they overlap). " +
                    "An artifact belongs to one component only; remove 'y' from one of them.",
            ),
            v,
        )
    }

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
    @DisplayName("a base claim is in force only within its component's supported versions (migrated archived component)")
    fun baseClaimClippedToCoverage() {
        // An archived component was migrated with ALL ownership of its group at all versions, but it is
        // supported only in its old DSL blocks. The legacy DSL validator compared each block's own range on
        // both sides, so a component supported only in later versions never met it there.
        val newCurrent =
            listOf(claim("current", group = "org.example.legacy", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("current")))
        val existingArchived = listOf(claim("archived", group = "org.example.legacy", mode = ArtifactIdMode.ALL))

        assertTrue(computeOwnershipCollisions(newCurrent, existingArchived, realIntersect).isNotEmpty())
        assertTrue(
            computeOwnershipCollisions(
                newCurrent,
                existingArchived,
                realIntersect,
                coverageByComponent =
                    mapOf("archived" to listOf("[2.4.62-0001,2.4.64-9999]"), "current" to listOf("[10,)")),
            ).isEmpty(),
        )
    }

    @Test
    @DisplayName("a per-range claim does not collide with a rival base outside the rival's supported versions")
    fun perRangeClaimVsBaseOutsideRivalCoverage() {
        val newOwner =
            listOf(claim("owner", group = "org.example.shared", mode = ArtifactIdMode.ALL, range = "[1.0.0,1.1.999)"))
        val existingRival =
            listOf(claim("rival", group = "org.example.shared", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("rival")))
        assertTrue(
            computeOwnershipCollisions(
                newOwner,
                existingRival,
                realIntersect,
                coverageByComponent = mapOf("rival" to listOf("[1.2.0,)")),
            ).isEmpty(),
        )
    }

    @Test
    @DisplayName("a base EXPLICIT claim limited to later versions does not collide with a rival's older per-range EXPLICIT claim")
    fun baseExplicitVsOlderOverrideOfSplitArtifact() {
        // An artifact first built inside an old component (per-range block) and later split into its own
        // component, numbered from a later version: the new owner's all-versions base is in force only there.
        val newSplit =
            listOf(claim("split", group = "org.example.sys", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("message")))
        val existingOld =
            listOf(
                claim(
                    "old",
                    group = "org.example.sys",
                    mode = ArtifactIdMode.EXPLICIT,
                    tokens = setOf("core", "message"),
                    range = "[1.0.36-0000, 1.0.37-0000)",
                ),
            )
        assertTrue(computeOwnershipCollisions(newSplit, existingOld, realIntersect).isNotEmpty())
        assertTrue(
            computeOwnershipCollisions(
                newSplit,
                existingOld,
                realIntersect,
                coverageByComponent = mapOf("split" to listOf("[11.0.1,)"), "old" to listOf("[1.0.36-0000,1.0.38-0000)")),
            ).isEmpty(),
        )
    }

    @Test
    @DisplayName("build-suffixed ranges (one with a space after the comma) parse and are pairwise disjoint")
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
        assertTrue(v.single().contains("for versions [1.5,1.6) vs versions [1.4,2.0) (they overlap)"), v.single())

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

    /** Both sides' own override ranges, as the service builds them from the mappings. */
    private fun overrideRanges(vararg claims: OwnershipClaim): Map<String, Set<String>> =
        claims
            .groupBy { it.componentKey }
            .mapValues { (_, cs) -> cs.map { it.versionRange }.filterTo(mutableSetOf()) { it != base } }

    private fun prodPair(
        newClaims: List<OwnershipClaim>,
        rivalClaims: List<OwnershipClaim>,
        coverage: Map<String, List<String>>,
        withSubtract: Boolean,
    ) = computeOwnershipCollisions(
        newClaims,
        rivalClaims,
        realIntersect,
        overrideRanges(*(newClaims + rivalClaims).toTypedArray()),
        coverage,
        if (withSubtract) subtract else null,
    )

    @Test
    @DisplayName("SYS-109: sub-range overrides: the rival's own overrides replace its base up to 1.0.39-361")
    fun `SYS-109 own sub-range overrides replace the base`() {
        val group = "org.example.shared"
        val older = listOf(claim("older", group, ArtifactIdMode.EXPLICIT, setOf("older-lib")))
        val newer =
            listOf(
                claim("newer", group, ArtifactIdMode.EXPLICIT, setOf("newer-lib", "older-lib")),
                claim("newer", group, ArtifactIdMode.EXPLICIT, setOf("newer-lib"), "[1.0.39,1.0.39-307)"),
                claim("newer", group, ArtifactIdMode.EXPLICIT, setOf("newer-lib"), "[1.0.39-307,1.0.39-361)"),
            )
        val coverage = mapOf("older" to listOf("(,1.0.39-361)"), "newer" to listOf("[1.0.39,)"))

        assertTrue(prodPair(older, newer, coverage, withSubtract = false).isNotEmpty())
        assertEquals(emptyList<String>(), prodPair(older, newer, coverage, withSubtract = true))
        // Symmetric: saving the newer component does not meet the older one either.
        assertEquals(emptyList<String>(), prodPair(newer, older, coverage, withSubtract = true))
    }

    @Test
    @DisplayName("SYS-109: an override equal to a coverage segment owns only its own token, the base is in force from 1.0")
    fun `SYS-109 override equal to a coverage segment replaces the base`() {
        val group = "org.example.storage"
        val client =
            listOf(
                claim(
                    "storage-client",
                    group,
                    ArtifactIdMode.EXPLICIT,
                    setOf("client", "client-parent", "gradle-client", "maven-plugin"),
                ),
            )
        val storage =
            listOf(
                claim(
                    "storage",
                    group,
                    ArtifactIdMode.EXPLICIT,
                    setOf("storage", "client", "gradle-client", "maven-plugin", "gradle-plugin"),
                ),
                claim("storage", group, ArtifactIdMode.EXPLICIT, setOf("storage"), "(,0.2)"),
            )
        val coverage = mapOf("storage-client" to listOf("[0.1,0.2)"), "storage" to listOf("(,0.2)", "[1.0,)"))

        assertTrue(prodPair(client, storage, coverage, withSubtract = false).isNotEmpty())
        assertEquals(emptyList<String>(), prodPair(client, storage, coverage, withSubtract = true))
    }

    @Test
    @DisplayName("SYS-109: an override of another group (spaced range) replaces both base groups")
    fun `SYS-109 override of another group replaces the base`() {
        val api =
            listOf(
                claim("web-api", "org.example.web", ArtifactIdMode.EXPLICIT, setOf("api-ops")),
                claim("web-api", "org.example.operations", ArtifactIdMode.EXPLICIT, setOf("api-ops")),
            )
        val model =
            listOf(
                claim("web-model", "org.example.webnext", ArtifactIdMode.EXPLICIT, setOf("api-ops", "web-model")),
                claim("web-model", "org.example.operations", ArtifactIdMode.EXPLICIT, setOf("api-ops", "web-model")),
                claim("web-model", "org.example.webnext", ArtifactIdMode.EXPLICIT, setOf("web-model"), "[3.52, 3.56)"),
            )
        val coverage =
            mapOf("web-api" to listOf("[2,3.56)"), "web-model" to listOf("[3.52,3.60.0-4061]", "[3.60.0-4062,)"))

        assertTrue(prodPair(api, model, coverage, withSubtract = false).isNotEmpty())
        assertEquals(emptyList<String>(), prodPair(api, model, coverage, withSubtract = true))
    }

    @Test
    @DisplayName("SYS-109: base minus own overrides keeps a real conflict outside the overridden range")
    fun `SYS-109 base minus own overrides keeps a real conflict`() {
        val a = listOf(claim("A", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old"), range = "[2.5,3)"))
        val b =
            listOf(
                claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old")),
                claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("new"), range = "[1,2)"),
            )
        val v = prodPair(a, b, mapOf("B" to listOf("[1,4)")), withSubtract = true)
        assertEquals(1, v.size)
        assertTrue(v.single().contains("for versions [2.5,3) vs versions [2,4) (they overlap)"), v.single())
        // A component that overrides all of its coverage has no base left in force, even against a
        // sub-range the exact-string shadow skip does not match.
        val inside = listOf(claim("A", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("old"), range = "[1.5,2)"))
        assertTrue(prodPair(inside, b, mapOf("B" to listOf("[1,2)")), withSubtract = false).isNotEmpty())
        assertEquals(emptyList<String>(), prodPair(inside, b, mapOf("B" to listOf("[1,2)")), withSubtract = true))
    }

    @Test
    @DisplayName("SYS-109: an override range the version factory rejects keeps the rival's base in force")
    fun `SYS-109 factory-rejected override range keeps the base`() {
        val factoryAccepts: (String) -> Boolean = { runCatching { versionRangeFactory.create(it) }.isSuccess }
        val strictSubtract: (List<String>, Collection<String>) -> List<String>? = { ranges, minus ->
            VersionRangePartition.subtract(ranges, minus, numericCompare, factoryAccepts)
        }
        val a = listOf(claim("A", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("shared"), range = "[0.2,0.4)"))
        val b =
            listOf(
                claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("shared")),
                claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("other"), range = "[,1)"),
            )
        val intersect: (String, String) -> Boolean = { x, y -> runCatching { realIntersect(x, y) }.getOrDefault(true) }
        val shadow = overrideRanges(*(a + b).toTypedArray())
        val coverage = mapOf("B" to listOf("[0,1)"))
        // The lenient parser alone would subtract all of B's coverage and hide the conflict.
        assertEquals(emptyList<String>(), computeOwnershipCollisions(a, b, intersect, shadow, coverage, subtract))
        val v = computeOwnershipCollisions(a, b, intersect, shadow, coverage, strictSubtract)
        assertEquals(1, v.size, v.toString())
    }

    @Test
    @DisplayName("SYS-109: an override range with whitespace inside a bound keeps the base")
    fun `SYS-109 override with whitespace inside a bound keeps the base`() {
        // The factory's `[1,2.3 )` (space or no-break space) does not contain 2.1, so B's base is in
        // force there: a real conflict.
        listOf("[1,2.3 )", "[1,2.3\u00A0)").forEach { override ->
            val a = listOf(claim("A", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("shared"), range = "[2.1,2.2)"))
            val b =
                listOf(
                    claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("shared")),
                    claim("B", mode = ArtifactIdMode.EXPLICIT, tokens = setOf("other"), range = override),
                )
            val shadow = overrideRanges(*(a + b).toTypedArray())
            val coverage = mapOf("B" to listOf("[1,3)"))
            val v = computeOwnershipCollisions(a, b, realIntersect, shadow, coverage, subtract)
            assertEquals(1, v.size, "$override: $v")
        }
    }
}
