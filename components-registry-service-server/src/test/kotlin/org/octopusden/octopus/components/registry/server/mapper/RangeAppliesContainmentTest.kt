package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * TD-010 acceptance matrix for [rangeApplies] — range-VIEW enumeration override selection.
 *
 * The predicate decides whether an override row keyed by parentRange should be projected onto an
 * enumeration view childRange. The contract is range containment (child is a subset of parent),
 * approximated by the sample-points heuristic specified in
 * docs/registry/tech-debt/010-range-applies-containment.md.
 *
 * Bounded cases 1-9 plus the parseable union cases 10-11 ship here. The unbounded case 12 (parent
 * "(,)") is deferred to TD-010-b: the underlying VersionRangeFactory rejects a both-sides-open
 * restriction at parse time ("Bad range: no minimum, maximum allowed versions are specified"), so a
 * sample-points containment check cannot be evaluated against it. The predicate falls back to
 * string equality when the parent fails to parse, which keeps "(,)" vs concrete child conservative
 * (false) rather than wrong. See the deferral note appended to the doc.
 */
class RangeAppliesContainmentTest {

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private val numericVersionFactory = NumericVersionFactory(versionNames)

    @Nested
    @DisplayName("bounded cases 1-9 (TD-010 acceptance bar)")
    inner class Bounded {
        @ParameterizedTest(name = "#{0}: rangeApplies(parent={1}, child={2}) == {3} — {4}")
        @MethodSource("org.octopusden.octopus.components.registry.server.mapper.RangeAppliesContainmentTest#boundedCases")
        fun matches(
            case: Int,
            parent: String,
            child: String,
            expected: Boolean,
            rationale: String,
        ) {
            assertEquals(
                expected,
                rangeApplies(parent, child, versionRangeFactory, numericVersionFactory),
                "case #$case: $rationale",
            )
        }
    }

    @Nested
    @DisplayName("union cases 10-11 (parseable; case 12 deferred to TD-010-b)")
    inner class Union {
        @ParameterizedTest(name = "#{0}: rangeApplies(parent={1}, child={2}) == {3} — {4}")
        @MethodSource("org.octopusden.octopus.components.registry.server.mapper.RangeAppliesContainmentTest#unionCases")
        fun matches(
            case: Int,
            parent: String,
            child: String,
            expected: Boolean,
            rationale: String,
        ) {
            assertEquals(
                expected,
                rangeApplies(parent, child, versionRangeFactory, numericVersionFactory),
                "case #$case: $rationale",
            )
        }
    }

    @Nested
    @DisplayName("robustness beyond the matrix — near-upper overshoot of an open bound")
    inner class Robustness {
        @org.junit.jupiter.api.Test
        @DisplayName("child overshoots parent only in the tail above the coarse grid (not contained)")
        fun openUpperOvershootBetweenGridPoints() {
            // child [1.5,2.9) escapes parent [1.0,2.5) only in (2.5,2.9); both endpoints and the whole
            // 2.0 grid point are inside the parent, so endpoint+grid sampling alone would wrongly pass.
            // The near-upper probe makes this correctly false.
            assertEquals(
                false,
                rangeApplies("[1.0,2.5)", "[1.5,2.9)", versionRangeFactory, numericVersionFactory),
                "child's open-upper tail past the parent must be detected",
            )
        }

        @org.junit.jupiter.api.Test
        @DisplayName("genuinely contained narrow child stays true")
        fun narrowContainedChildStaysTrue() {
            assertEquals(
                true,
                rangeApplies("[1.0,3.0)", "[1.5,2.9)", versionRangeFactory, numericVersionFactory),
                "a child fully inside a wider parent must remain a match",
            )
        }

        @org.junit.jupiter.api.Test
        @DisplayName("wide child (9+ major span) overshooting the parent is still detected (probe-cap guard)")
        fun wideSpanOvershootStillDetected() {
            // child [0.5,8.9) spans 9 majors, so the whole-version grid points (1.0..8.0) alone would
            // fill the interior-probe cap; the near-upper probe just below 8.9 must still be sampled so
            // the overshoot past the parent's 8.5 upper is caught. Guards the offer-near-upper-first
            // ordering in interiorProbes.
            assertEquals(
                false,
                rangeApplies("[0.5,8.5)", "[0.5,8.9)", versionRangeFactory, numericVersionFactory),
                "a wide child overshooting the parent's open upper must not be a false match",
            )
        }
    }

    @Nested
    @DisplayName("case 12 deferred — unbounded parent falls back to string equality (conservative)")
    inner class UnboundedDeferred {
        @org.junit.jupiter.api.Test
        @DisplayName("#12: parent \"(,)\" vs concrete child is conservatively false (parse-fail fallback)")
        fun unboundedParentFallsBackToEquality() {
            // TD-010-b: "(,)" is unparseable by VersionRangeFactory, so the heuristic cannot run and
            // we fall back to parent == child. Asserting the documented conservative behaviour here
            // (false), NOT the eventual TD-010-b target (true), so the deferral is explicit in code.
            assertEquals(
                false,
                rangeApplies("(,)", "[1.0,2.0)", versionRangeFactory, numericVersionFactory),
                "case #12 deferred: unbounded parent cannot be parsed; conservative false until TD-010-b",
            )
        }
    }

    companion object {
        @JvmStatic
        fun boundedCases(): List<Arguments> =
            listOf(
                Arguments.of(1, "[1.0,2.0)", "[1.0,2.0)", true, "exact equality (preserved)"),
                Arguments.of(2, "[1.0,3.0)", "[1.0,2.0)", true, "strict left-aligned containment"),
                Arguments.of(3, "[1.0,3.0)", "[2.0,3.0)", true, "strict right-aligned containment"),
                Arguments.of(4, "[1.0,3.0)", "[1.5,2.5)", true, "strict interior containment"),
                Arguments.of(5, "[1.0,2.0)", "[1.5,2.5)", false, "partial overlap, child extends past parent"),
                Arguments.of(6, "[1.0,2.0]", "[2.0,3.0)", false, "single-point intersection at closed boundary, NOT containment"),
                Arguments.of(7, "[1.0,2.0)", "[2.0,3.0)", false, "adjacent disjoint, no overlap"),
                Arguments.of(8, "(1.0,3.0)", "[1.5,2.5]", true, "strict interior, mixed bound styles"),
                Arguments.of(9, "[1.0,2.0)", "[1.0,2.0]", false, "child closed upper exceeds parent open upper by epsilon"),
            )

        @JvmStatic
        fun unionCases(): List<Arguments> =
            // NOTE on revert-guarding: case 10 expects `true`, which the old exact-match predicate
            // ("(,0),[1.0,)" == "[2.0,3.0)" → false) could NOT produce — so case 10 actively guards the
            // new containment behaviour. Case 11 expects `false`, which the old predicate ALSO returns
            // (string inequality), so case 11 is a conservative documentation case, NOT a live revert
            // guard. The behaviour-guarding load is carried by bounded cases 2/3/4/8 (true) + case 10.
            listOf(
                Arguments.of(10, "(,0),[1.0,)", "[2.0,3.0)", true, "union-parent contains right-segment-only child"),
                // Doc case 11 prints the child as [-1.0,0.5); negative versions are unsupported by the
                // factory (the '-' is a separator, so "-1.0" parses to "1.0" and "[1.0,0.5)" then fails
                // the min<=max check). Substitute the semantically equivalent gap-straddling child
                // [0.5,1.5): its lower endpoint 0.5 lies in the union-parent's gap [0,1.0), so it is not
                // contained. Intent preserved; recorded in the doc.
                Arguments.of(11, "(,0),[1.0,)", "[0.5,1.5)", false, "child straddles a gap in the union-parent"),
            )
    }
}
