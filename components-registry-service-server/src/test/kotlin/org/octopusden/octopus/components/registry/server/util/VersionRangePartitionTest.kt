package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/** Unit tests for [VersionRangePartition] (ADR-018 redesign: coverage merge + enumeration partition). */
class VersionRangePartitionTest {

    // ── canonical render forms (EXACT strings — these catch rendering regressions locally,
    //    before the stand baseline; the bug we missed was [x] rendered as [x,x]) ─────────────

    @Test
    @DisplayName("render forms are byte-identical to V1: [x] stays [x], composites/bounds unchanged")
    fun `canonical render forms`() {
        // Single-version block round-trips to the hard-version form, NOT [x,x].
        assertEquals(listOf("[1.0.49]"), VersionRangePartition.mergeUnion(listOf("[1.0.49]")))
        assertEquals(listOf("[3.5.0]"), VersionRangePartition.mergeUnion(listOf("[3.5.0]")))
        // Bounded / open-upper / open-lower keep their exact bracket + bound forms.
        assertEquals(listOf("[1,2)"), VersionRangePartition.mergeUnion(listOf("[1,2)")))
        assertEquals(listOf("[2,)"), VersionRangePartition.mergeUnion(listOf("[2,)")))
        assertEquals(listOf("(,3]"), VersionRangePartition.mergeUnion(listOf("(,3]")))
        // partition emits a single-version sub-range as [x] too (an inclusive edge landing on a point).
        assertEquals(listOf("[1,5]"), VersionRangePartition.mergeUnion(listOf("[1,5]")))
        // disjoint single-version blocks stay separate, each in [x] form.
        assertEquals(listOf("[1.0.2]", "[1.0.14]"), VersionRangePartition.mergeUnion(listOf("[1.0.2]", "[1.0.14]")))
    }

    // ── scheme-aware comparator + single-version parse (stand-compat regressions) ───

    @Test
    @DisplayName("single-version Maven range [x] survives mergeUnion (single-version-block gap-404 regression)")
    fun `mergeUnion keeps single-version ranges`() {
        val names = VersionNames("serviceCBranch", "serviceC", "minorC")
        val vrf = VersionRangeFactory(names)
        val nvf = NumericVersionFactory(names)
        val cmp: (String, String) -> Int = { a, b -> nvf.create(a).compareTo(nvf.create(b)) }

        // The Maven hard-version form `[2.2.5-0000]` (no comma) must NOT be dropped — before the fix
        // parseSegment's comma-requiring regex returned null, so mergeUnion silently produced an empty
        // coverage set and the resolve gate 404'd version 2.2.5 (== 2.2.5-0000 in this scheme).
        val merged = VersionRangePartition.mergeUnion(listOf("[2.2.5-0000]"), cmp)
        // EXACT string, not just containment: V1 renders a single-version block verbatim as `[x]`, so
        // we must too — `[x,x]` would round-trip-contain the version yet still produce a KEY_MISSING
        // enumeration diff vs V1. (A containment-only assertion silently passed the `[x,x]` regression.)
        assertEquals(listOf("[2.2.5-0000]"), merged, "single-version block must render as [x], not [x,x] or be dropped")
        assertTrue(
            vrf.create(merged.single()).containsVersion(nvf.create("2.2.5")),
            "merged coverage must contain 2.2.5 (== 2.2.5-0000 in scheme); got ${merged.single()}",
        )

        // Mixed with neighbouring ranges (the real multi-block shape): the singleton stays covered.
        val mixed = VersionRangePartition.mergeUnion(
            listOf("[2,2.1.18)", "[2.2.5-0000]", "(2.2.5-0000,2.2.7-0007)"),
            cmp,
        )
        assertTrue(
            mixed.any { vrf.create(it).containsVersion(nvf.create("2.2.5")) },
            "2.2.5 must be covered by some merged segment; got $mixed",
        )
    }

    @Test
    @DisplayName("partition uses the INJECTED comparator so dash-qualifier ranges never invert")
    fun `partition with scheme comparator yields factory-valid ranges`() {
        // Mixed dash/dot edge forms (0.7-157, 0.7-803, 0.7.157). The component's
        // scheme (NumericVersionFactory/VersionRangeFactory, both from VersionNames) orders these so the
        // produced sub-ranges are well-formed. Maven's DefaultArtifactVersion orders them differently and
        // would emit an inverted (0.7-803,0.7.157] that the factory rejects ("left > right" → HTTP 400).
        val names = VersionNames("serviceCBranch", "serviceC", "minorC")
        val nvf = NumericVersionFactory(names)
        val vrf = VersionRangeFactory(names)
        val schemeCompare: (String, String) -> Int = { a, b -> nvf.create(a).compareTo(nvf.create(b)) }

        val ranges =
            VersionRangePartition.partition(
                listOf("[0.7,2)"),
                listOf("[0.7,0.7-157]", "(0.7-803,2)", "[0.7.157,0.7-803]"),
                schemeCompare,
            )

        // Every produced range must be accepted by the SAME factory that validates them at runtime —
        // i.e. no inverted bounds. (vrf.create throws "Bad version range …" on left > right.)
        ranges.forEach { r -> assertDoesNotThrow({ vrf.create(r) }, "partition produced a factory-invalid range: $r") }
    }

    // ── partition ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("override edge inside a bounded segment splits it; redundant adjacency stays whole")
    fun `partition bounded by one edge`() {
        assertEquals(listOf("[1,2)", "[2,3)", "[3,10)"), VersionRangePartition.partition(listOf("[1,10)"), listOf("[2,3)")))
        // no edges → segment stays whole (redundant-identical collapse)
        assertEquals(listOf("[1,10)"), VersionRangePartition.partition(listOf("[1,10)"), emptyList()))
    }

    @Test
    @DisplayName("ALL_VERSIONS with no edges enumerates as the canonical sentinel (byte-identical to M1)")
    fun `partition all-versions no edges`() {
        assertEquals(listOf("(,0),[0,)"), VersionRangePartition.partition(listOf("(,0),[0,)"), emptyList()))
    }

    @Test
    @DisplayName("ALL_VERSIONS with an open-upper edge partitions into (,X) and [X,)")
    fun `partition all-versions by open-upper edge`() {
        assertEquals(listOf("(,2)", "[2,)"), VersionRangePartition.partition(listOf("(,0),[0,)"), listOf("[2,)")))
    }

    @Test
    @DisplayName("coincident edges from different sources dedup by numeric value (no double split)")
    fun `partition dedups coincident edges`() {
        // override [2,3) (listed first) and ownership [2.0,5): boundary 2 ≡ 2.0 dedups → no double split.
        assertEquals(
            listOf("[1,2)", "[2,3)", "[3,5)", "[5,10)"),
            VersionRangePartition.partition(listOf("[1,10)"), listOf("[2,3)", "[2.0,5)")),
        )
    }

    @Test
    @DisplayName("open-lower / inclusive-upper override edges carry their inclusivity into the split")
    fun `partition carries inclusivity`() {
        assertEquals(listOf("[1,2]", "(2,3)", "[3,10)"), VersionRangePartition.partition(listOf("[1,10)"), listOf("(2,3)")))
        assertEquals(listOf("[1,2)", "[2,3]", "(3,10)"), VersionRangePartition.partition(listOf("[1,10)"), listOf("[2,3]")))
    }

    @Test
    @DisplayName("two open-adjacent edges excluding the same point isolate it as a singleton [p,p] (GAP)")
    fun `partition isolates a both-excluded boundary point`() {
        // overrides [1,2) and (2,3] both EXCLUDE point 2 → 2 resolves to base, so it must be its own
        // singleton view between the two open neighbours, not swallowed by either side.
        assertEquals(
            listOf("[1,2)", "[2]", "(2,3]", "(3,10)"),
            VersionRangePartition.partition(listOf("[1,10)"), listOf("[1,2)", "(2,3]")),
        )
        // closed-open adjacency (the legacy/migrated shape) does NOT gap: 2 belongs to the right piece.
        assertEquals(
            listOf("[1,2)", "[2,3)", "[3,10)"),
            VersionRangePartition.partition(listOf("[1,10)"), listOf("[1,2)", "[2,3)")),
        )
    }

    @Test
    @DisplayName("boundary carve: a GAP singleton at an inclusive lo carves [lo]; an exclusive hi is NOT carved")
    fun `partition carves boundary singletons only at inclusive bounds`() {
        // Inclusive lo + GAP edges `[1,2)`,`(2,5)` exclude point 2... but here test the boundary itself:
        // coverage [2,10) with a singleton override [2] (GAP at the inclusive lo 2) → carve [2], then (2,10).
        assertEquals(listOf("[2]", "(2,10)"), VersionRangePartition.partition(listOf("[2,10)"), listOf("[2]")))
        // Same singleton but coverage with EXCLUSIVE lo (2,10): point 2 is not in the segment → no carve.
        assertEquals(listOf("(2,10)"), VersionRangePartition.partition(listOf("(2,10)"), listOf("[2]")))
        // GAP singleton at an inclusive hi: coverage (0,2] with singleton [2] → (0,2) then [2].
        assertEquals(listOf("(0,2)", "[2]"), VersionRangePartition.partition(listOf("(0,2]"), listOf("[2]")))
    }

    @Test
    @DisplayName("multiple supported segments each partitioned independently; edges outside a segment ignored")
    fun `partition multiple segments`() {
        // edge [2,) → point 2 (only inside [1,3)); edge [6,7) → points 6 AND 7 (both inside [5,8)).
        assertEquals(
            listOf("[1,2)", "[2,3)", "[5,6)", "[6,7)", "[7,8)"),
            VersionRangePartition.partition(listOf("[1,3)", "[5,8)"), listOf("[2,)", "[6,7)")),
        )
    }

    // ── mergeUnion ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adjacent contiguous ranges merge into one; the canonical user case")
    fun `merge contiguous`() {
        assertEquals(
            listOf("[1.2,1.5.1400)"),
            VersionRangePartition.mergeUnion(listOf("[1.2,1.3)", "[1.3,1.4)", "[1.4,1.5)", "[1.5.0,1.5.1400)")),
        )
    }

    @Test
    @DisplayName("a gap keeps the ranges separate (M8-style)")
    fun `merge keeps gap separate`() {
        assertEquals(listOf("[1,2)", "[5,)"), VersionRangePartition.mergeUnion(listOf("[1,2)", "[5,)")))
        // composite input flattened then merged: [1,2),[5,) gap preserved
        assertEquals(listOf("[1,2)", "[5,)"), VersionRangePartition.mergeUnion(listOf("[1,2),[5,)")))
    }

    @Test
    @DisplayName("overlapping ranges merge; touching at a point with one side closed merges")
    fun `merge overlap and touch`() {
        assertEquals(listOf("[1,5)"), VersionRangePartition.mergeUnion(listOf("[1,3)", "[2,5)")))
        assertEquals(listOf("[1,5)"), VersionRangePartition.mergeUnion(listOf("[1,2)", "[2,5)")))
        assertEquals(listOf("[1,5]"), VersionRangePartition.mergeUnion(listOf("[2,5]", "[1,3)")))
    }

    @Test
    @DisplayName("any all-versions input collapses the whole set to the canonical sentinel")
    fun `merge all-versions`() {
        assertEquals(listOf("(,0),[0,)"), VersionRangePartition.mergeUnion(listOf("[1,2)", "(,0),[0,)")))
        assertEquals(listOf("(,0),[0,)"), VersionRangePartition.mergeUnion(listOf("(,)")))
    }
}
