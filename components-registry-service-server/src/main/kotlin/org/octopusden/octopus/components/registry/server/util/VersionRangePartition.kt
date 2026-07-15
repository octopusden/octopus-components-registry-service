package org.octopusden.octopus.components.registry.server.util

import org.apache.maven.artifact.versioning.DefaultArtifactVersion

/**
 * Pure version-range algebra for the decoupled version model (ADR-018 redesign): coverage is stored
 * as a MERGED union (override-independent) and range-view ENUMERATION is the partition of supported
 * by value-change edges. Two operations:
 *
 *  - [partition] — split supported segments at the union of value-change edge points, so each
 *    resulting sub-range has constant resolved values. Adjacent sub-ranges with no edge between them
 *    stay one range (redundant-identical legacy blocks collapse). Handles the `ALL_VERSIONS` sentinel
 *    as the unbounded `(−∞,+∞)` segment.
 *  - [mergeUnion] — collapse a set of declared/requested ranges into maximal contiguous single
 *    intervals (used by migration and the supported-versions PUT to store clean coverage).
 *
 * String-in / string-out, unit-testable without Spring/DB. Ranges use Maven syntax: `[1.0,2.0)`,
 * `[2.0,)`, `(,3.0]`, composites `[1,2),[5,)`. Version ordering is INJECTED via `compare`: production
 * callers MUST pass the component's `NumericVersionFactory` ordering (see `numericVersionComparator`)
 * so edges sort/dedup consistently with the `VersionRangeFactory` that validates the output ranges;
 * the default (Maven `DefaultArtifactVersion`) is for unit tests / simple integer-component ranges
 * only and would invert dash-qualifier ranges like `0.7-803` in production.
 */
object VersionRangePartition {
    /** A single half-open/closed interval; a null bound is unbounded (open) on that side. */
    internal data class Segment(
        val lo: String?,
        val loIncl: Boolean,
        val hi: String?,
        val hiIncl: Boolean,
    )

    /** A breakpoint value plus whether that value belongs to the LEFT (lower) piece when splitting. */
    private data class Edge(
        val value: String,
        val inLeft: Boolean,
    )

    /**
     * A coincident-endpoint group at one numeric [value]. [sawLeft] = at least one edge wants the value
     * in the left piece (closed-upper / open-lower); [sawRight] = at least one wants it in the right
     * (open-upper / closed-lower). Both set ⇒ a `[…,p)` meets a `(p,…]` and p is excluded by both
     * (GAP → singleton `[p,p]`); exactly one set ⇒ an ordinary LEFT/RIGHT split.
     */
    private data class Breakpoint(
        val value: String,
        val sawLeft: Boolean,
        val sawRight: Boolean,
    )

    private val SIMPLE_SEGMENT_PATTERN = Regex("^([\\[(])([^,]*),([^,]*)([\\])])$")

    // Maven hard-version form `[x]` (no comma): exactly version x.
    private val SINGLE_VERSION_PATTERN = Regex("^\\[([^,\\[\\]()]+)\\]$")
    internal const val ALL_VERSIONS_SENTINEL = "(,0),[0,)"

    internal fun normalize(range: String): String = range.trim().replace(Regex("\\s+"), "")

    /** True for the all-versions shapes (null / `(,0),[0,)` / `(,)`). */
    internal fun isAllVersions(range: String?): Boolean {
        if (range == null) return true
        val n = normalize(range)
        return n == ALL_VERSIONS_SENTINEL || n == "(,)"
    }

    /** Parse a single-segment range; `null` for a composite (multi-segment) or malformed string. */
    internal fun parseSegment(range: String): Segment? {
        val n = normalize(range)
        // Maven hard-version form `[x]` (no comma) = exactly version x → the closed singleton [x,x].
        // Without this, single-version DSL blocks like `[2.2.5-0000]` fail the comma-requiring pattern
        // below and are SILENTLY DROPPED from mergeUnion/partition, losing that version from coverage
        // (the resolve gate then 404s a version the old stack served).
        SINGLE_VERSION_PATTERN.matchEntire(n)?.let { sv ->
            val v = sv.groupValues[1].trim()
            if (v.isNotEmpty()) return Segment(lo = v, loIncl = true, hi = v, hiIncl = true)
        }
        val m = SIMPLE_SEGMENT_PATTERN.matchEntire(n) ?: return null
        val (open, lo, hi, close) = m.destructured
        if (lo.any { it in "()[]" } || hi.any { it in "()[]" }) return null
        return Segment(
            lo = lo.trim().ifEmpty { null },
            loIncl = open == "[",
            hi = hi.trim().ifEmpty { null },
            hiIncl = close == "]",
        )
    }

    /** Flatten a (possibly composite) range string into its single-interval segments. */
    private fun toSegments(range: String): List<Segment> {
        if (isAllVersions(range)) return listOf(Segment(null, false, null, false))
        parseSegment(range)?.let { return listOf(it) }
        // Composite: split on top-level commas between segments (e.g. "[1,2),[5,)").
        return SEGMENT_GLOBAL.findAll(normalize(range)).mapNotNull { parseSegment(it.value) }.toList()
    }

    // Matches a comma-form segment `[a,b)` OR a single-version segment `[x]` inside a composite.
    private val SEGMENT_GLOBAL = Regex("[\\[(][^()\\[\\],]*,[^()\\[\\],]*[\\])]|\\[[^()\\[\\],]+\\]")

    internal fun render(seg: Segment): String {
        // The unbounded-both interval IS all-versions — render the canonical sentinel so an
        // all-versions component with no value-change edges enumerates byte-identically to before.
        if (seg.lo == null && seg.hi == null) return ALL_VERSIONS_SENTINEL
        // An inclusive single-version interval renders in Maven hard-version form `[x]`, NOT `[x,x]`,
        // to match V1's verbatim block rendering — otherwise every single-version block produces a
        // KEY_MISSING enumeration diff (`[x]` baseline vs `[x,x]` candidate).
        if (seg.lo != null && seg.lo == seg.hi && seg.loIncl && seg.hiIncl) return "[${seg.lo}]"
        return buildString {
            append(if (seg.loIncl) '[' else '(')
            append(seg.lo ?: "")
            append(',')
            append(seg.hi ?: "")
            append(if (seg.hiIncl) ']' else ')')
        }
    }

    /**
     * Ordering used when the caller supplies no scheme-aware comparator (unit tests / simple
     * integer-component ranges). PRODUCTION CALLERS MUST pass the component's `NumericVersionFactory`
     * comparator instead: `DefaultArtifactVersion` orders dash-qualifier versions (`0.7-803`) and
     * dot versions (`0.7.157`) differently from the registry's `VersionRangeFactory`, so using it here
     * makes partition emit edges in an order the factory then sees as inverted (`left > right`) — it
     * builds a backwards range like `(0.7-803,0.7.157]` and the factory rejects it (HTTP 400).
     */
    internal fun defaultVersionCompare(
        a: String,
        b: String,
    ): Int = DefaultArtifactVersion(a).compareTo(DefaultArtifactVersion(b))

    /** True iff finite [point] lies strictly inside [seg] (unbounded side = ±∞). */
    private fun strictlyInside(
        seg: Segment,
        point: String,
        compare: (String, String) -> Int,
    ): Boolean {
        val aboveLo = seg.lo == null || compare(point, seg.lo) > 0
        val belowHi = seg.hi == null || compare(point, seg.hi) < 0
        return aboveLo && belowHi
    }

    /**
     * Partition each supported [segments] range by the value-change [edgeRanges] (amendment A).
     * `segments` are single intervals or the `ALL_VERSIONS` sentinel (post-migration coverage is
     * always single contiguous segments). Edge points are deduped by numeric value (so `[3,)` and
     * `[3.0,)` collapse). Order preserved; duplicate sub-ranges removed.
     */
    fun partition(
        segments: List<String>,
        edgeRanges: List<String>,
        compare: (String, String) -> Int = ::defaultVersionCompare,
    ): List<String> {
        // Collect candidate breakpoints from every edge range's finite endpoints, tagged with which
        // piece the value joins (open-lower / closed-upper → left; closed-lower / open-upper → right).
        val rawEdges =
            edgeRanges.flatMap { e ->
                toSegments(e).flatMap { seg ->
                    buildList {
                        seg.lo?.let { add(Edge(it, inLeft = !seg.loIncl)) }
                        seg.hi?.let { add(Edge(it, inLeft = seg.hiIncl)) }
                    }
                }
            }
        // Group coincident endpoints by NUMERIC value (`[3,)` and `[3.0,)` collapse because
        // cmp("3","3.0") == 0) and classify each boundary by which piece the value belongs to:
        //   LEFT  — every edge there is closed-upper / open-lower → the value closes the left piece
        //           inclusively (`…,p]`) and the right piece opens after it (`(p,…`).
        //   RIGHT — every edge is open-upper / closed-lower → the left piece ends before it (`…,p)`)
        //           and the value opens the right piece inclusively (`[p,…`).
        //   GAP   — MIXED: a `[…,p)` edge meets a `(p,…]` edge, so p is excluded by BOTH neighbours.
        //           p is a zero-width point that resolves to base; isolate it as a singleton `[p,p]`
        //           between the two open neighbours so neither neighbour's view wrongly swallows it.
        // (GAP is unreachable from migrated data — A1: legacy blocks are closed-open adjacent — but a
        // v4-API open-lower override can create it; without this the singleton point would mis-resolve.)
        val buckets = mutableListOf<Breakpoint>()
        for (edge in rawEdges) {
            val idx = buckets.indexOfFirst { compare(it.value, edge.value) == 0 }
            if (idx < 0) {
                buckets += Breakpoint(edge.value, sawLeft = edge.inLeft, sawRight = !edge.inLeft)
            } else {
                val b = buckets[idx]
                buckets[idx] = b.copy(sawLeft = b.sawLeft || edge.inLeft, sawRight = b.sawRight || !edge.inLeft)
            }
        }

        val result = mutableListOf<String>()

        fun emit(piece: String) {
            if (piece !in result) result += piece
        }

        // Render+emit a piece, skipping degenerate intervals (lo>hi, or lo==hi unless both-inclusive) so
        // boundary carving can never produce an empty range like `(p,p)`.
        fun emitSeg(s: Segment) {
            if (s.lo != null && s.hi != null) {
                val c = compare(s.lo, s.hi)
                if (c > 0 || (c == 0 && !(s.loIncl && s.hiIncl))) return
            }
            emit(render(s))
        }
        for (segStr in segments) {
            val seg = toSegments(segStr).singleOrNull()
            if (seg == null) {
                // A composite supported segment (should not occur after migration-merge) — leave whole.
                emit(normalize(segStr))
                continue
            }
            // A value-change edge coincident with the segment's own lo/hi is NOT strictlyInside, so carve
            // the boundary point as its own view first — otherwise its (distinct) resolved value is
            // silently swallowed into one merged range. Carve `[lo]` when an edge CLOSES at lo
            // (sawLeft — LEFT or GAP: the value at lo differs from above), and `[hi]` when an edge OPENS
            // at hi (sawRight — RIGHT or GAP: the value at hi differs from below). Examples from live data:
            //   • singleton markers `[2.6.145]`/`[2.6.179]` at coverage `[2.6.145,2.6.179]` (GAP both ends)
            //   • a marker `[1.1.41,1.1.49)` ending EXCLUSIVE at coverage hi 1.1.49 → 1.1.49 = base (RIGHT at hi)
            // This never over-splits: a `sawLeft`/`sawRight` edge at an inclusive boundary IS by definition
            // a value-change point (an override closes/opens exactly there), so the carved boundary view
            // always resolves to a different value than its neighbour — never a spurious same-value split.
            val effLo = seg.lo
            var effLoIncl = seg.loIncl
            val effHi = seg.hi
            var effHiIncl = seg.hiIncl
            // Only carve when the boundary point is actually IN the segment (inclusive bound) — an
            // exclusive bound (e.g. `[1.0,2.0)`) does not contain its endpoint, so an edge there is the
            // normal hand-off to the adjacent segment, not a singleton to isolate.
            val carveAtLo = seg.lo != null && seg.loIncl && buckets.any { compare(it.value, seg.lo) == 0 && it.sawLeft }
            val carveAtHi = seg.hi != null && seg.hiIncl && buckets.any { compare(it.value, seg.hi) == 0 && it.sawRight }
            val tail = mutableListOf<Segment>()
            if (carveAtLo) {
                emitSeg(Segment(seg.lo, true, seg.lo, true)) // [lo]
                effLoIncl = false // remaining segment opens after lo (boundary VALUE unchanged)
            }
            if (carveAtHi) {
                tail += Segment(seg.hi, true, seg.hi, true) // [hi] — emitted after the interior pieces
                effHiIncl = false // remaining segment closes before hi (boundary VALUE unchanged)
            }
            val effSeg = Segment(effLo, effLoIncl, effHi, effHiIncl)
            val interior =
                buckets
                    .filter { strictlyInside(effSeg, it.value, compare) }
                    .sortedWith { a, b -> compare(a.value, b.value) }
            var curLo = effLo
            var curLoIncl = effLoIncl
            for (bp in interior) {
                val gap = bp.sawLeft && bp.sawRight
                if (gap) {
                    emitSeg(Segment(curLo, curLoIncl, bp.value, false)) // left piece, p excluded
                    emitSeg(Segment(bp.value, true, bp.value, true)) // singleton [p,p]
                    curLo = bp.value
                    curLoIncl = false // right piece opens after p (exclusive)
                } else {
                    emitSeg(Segment(curLo, curLoIncl, bp.value, bp.sawLeft))
                    curLo = bp.value
                    curLoIncl = !bp.sawLeft
                }
            }
            emitSeg(Segment(curLo, curLoIncl, effHi, effHiIncl))
            tail.forEach { emitSeg(it) }
        }
        return result
    }

    /**
     * True iff [range]'s trailing segment has an empty (open-ended) upper bound, i.e. the range runs
     * to +inf — "[1.0,)", "(,0),[1.0,)", and the fully-unbounded "(,)". False for any finite or closed
     * upper bound ("[1.0,2.0)", "(,3]") and for a hard single version ("[1.0]"). This is the shared
     * range-shape primitive: `EntityMappers.rangeApplies` uses it so an open-upper child is only matched
     * against an open-upper parent, and migration base-row selection uses it to pick the newest block.
     *
     * String-based (matches the range verbatim) so it agrees with the loader's stored bracket forms
     * without needing scheme-aware parsing.
     */
    fun isOpenUpper(range: String): Boolean {
        val trimmed = range.trim()
        if (trimmed.isEmpty() || trimmed.last() != ')') return false
        val lastComma = trimmed.lastIndexOf(',')
        return lastComma in 0 until trimmed.length - 1 &&
            trimmed.substring(lastComma + 1, trimmed.length - 1).isBlank()
    }

    /**
     * Comparator over version-range STRINGS ranking them as BASE-row candidates for the decoupled
     * model (ADR-018): the base row must carry the effective **open-upper (newest) value**, so the
     * GREATEST range under this comparator is the block whose scalars seed the base. Feed the winner
     * to `maxWith`/`maxWithOrNull`.
     *
     * Ranking (ascending — bigger = better base):
     *  1. an open-upper range (runs to +inf) outranks any finite range — the newest versions live there;
     *  2. among equal open-upper-ness, the higher floor wins (`[2.0,)` beats `[1.0,)`; a range whose
     *     highest segment-floor is unbounded-left ranks lowest);
     *  3. tie-break on the higher ceiling (unbounded-upper = +inf ranks highest);
     *  4. final deterministic tie-break on the normalized string, so selection is stable across runs.
     *
     * [compare] MUST be the component's scheme-aware ordering (`numericVersionComparator`) so dash- and
     * dot-qualified versions (`2.1.108-290`) order the same way the `VersionRangeFactory` validates them.
     */
    fun baseCandidateComparator(compare: (String, String) -> Int): Comparator<String> =
        Comparator { a, b ->
            val ao = isOpenUpper(a) && !isAllVersions(a)
            val bo = isOpenUpper(b) && !isAllVersions(b)
            if (ao != bo) {
                if (ao) 1 else -1
            } else {
                val floorCmp =
                    compareNullableBound(highestFloor(a, compare), highestFloor(b, compare), compare, nullRanksHigh = false)
                if (floorCmp != 0) {
                    floorCmp
                } else {
                    val ceilCmp =
                        compareNullableBound(highestCeiling(a, compare), highestCeiling(b, compare), compare, nullRanksHigh = true)
                    if (ceilCmp != 0) ceilCmp else normalize(a).compareTo(normalize(b))
                }
            }
        }

    /** The greatest lower bound across [range]'s segments (`null` = some segment is unbounded-left). */
    private fun highestFloor(
        range: String,
        compare: (String, String) -> Int,
    ): String? = toSegments(range).mapNotNull { it.lo }.maxWithOrNull(Comparator(compare))

    /** The greatest upper bound across [range]'s segments (`null` = some segment runs to +inf). */
    private fun highestCeiling(
        range: String,
        compare: (String, String) -> Int,
    ): String? {
        val segs = toSegments(range)
        if (segs.any { it.hi == null }) return null
        return segs.mapNotNull { it.hi }.maxWithOrNull(Comparator(compare))
    }

    /**
     * Compare two bounds where `null` is unbounded. [nullRanksHigh] picks the direction: for a CEILING
     * an unbounded (+inf) bound is the greatest; for a FLOOR an unbounded (−inf) bound is the least.
     */
    private fun compareNullableBound(
        a: String?,
        b: String?,
        compare: (String, String) -> Int,
        nullRanksHigh: Boolean,
    ): Int =
        when {
            a == null && b == null -> 0
            a == null -> if (nullRanksHigh) 1 else -1
            b == null -> if (nullRanksHigh) -1 else 1
            else -> compare(a, b)
        }

    /**
     * Collapse [ranges] (possibly composite) into maximal contiguous single intervals (amendment D).
     * Two segments merge iff their union is a single interval — they overlap, or touch at the same
     * point with at least one side inclusive there (`[1,2)`+`[2,5)`→`[1,5)`; `[1,2)`+`[3,5)` stays
     * two; the all-versions sentinel collapses everything to a single `ALL_VERSIONS`). Output sorted
     * by lower bound, rendered.
     */
    fun mergeUnion(
        ranges: List<String>,
        compare: (String, String) -> Int = ::defaultVersionCompare,
    ): List<String> {
        if (ranges.any { isAllVersions(it) }) return listOf(ALL_VERSIONS_SENTINEL)
        val segs = ranges.flatMap { toSegments(it) }
        if (segs.isEmpty()) return emptyList()
        // Sort by lower bound (null lo = −∞ first) using the SAME scheme-aware comparator, then by
        // inclusivity (inclusive lower first). A Maven-only sort here would mis-order dash-qualifier
        // bounds and merge/leave them wrong, the coverage-side twin of the partition bug.
        val sorted =
            segs.sortedWith(
                Comparator { x, y ->
                    val lx = x.lo
                    val ly = y.lo
                    // inclusive-lower ranks before exclusive-lower at the same bound.
                    val inclTie = (if (x.loIncl) 0 else 1) - (if (y.loIncl) 0 else 1)
                    when {
                        lx == null && ly == null -> inclTie
                        lx == null -> -1 // −∞ sorts first
                        ly == null -> 1
                        else -> compare(lx, ly).let { if (it != 0) it else inclTie }
                    }
                },
            )
        val merged = mutableListOf(sorted.first())
        for (next in sorted.drop(1)) {
            val cur = merged.last()
            if (contiguousOrOverlapping(cur, next, compare)) {
                merged[merged.lastIndex] = unionOf(cur, next, compare)
            } else {
                merged += next
            }
        }
        return merged.map { render(it) }
    }

    /** True iff `a` (with a ≤ b by lower bound) overlaps or is point-adjacent to `b`. */
    private fun contiguousOrOverlapping(
        a: Segment,
        b: Segment,
        compare: (String, String) -> Int,
    ): Boolean {
        if (a.hi == null) return true // a runs to +∞ → covers b's start
        if (b.lo == null) return true // b starts at −∞
        val c = compare(a.hi, b.lo)
        return when {
            c > 0 -> true // a.hi past b.lo → overlap
            c < 0 -> false // gap
            else -> a.hiIncl || b.loIncl // touch at the same point → adjacent iff either side closed there
        }
    }

    /** Union of two contiguous/overlapping segments (a starts no later than b). */
    private fun unionOf(
        a: Segment,
        b: Segment,
        compare: (String, String) -> Int,
    ): Segment {
        val (hi, hiIncl) =
            when {
                a.hi == null || b.hi == null -> null to false
                else ->
                    when (compare(a.hi, b.hi)) {
                        0 -> a.hi to (a.hiIncl || b.hiIncl)
                        in 1..Int.MAX_VALUE -> a.hi to a.hiIncl
                        else -> b.hi to b.hiIncl
                    }
            }
        return Segment(lo = a.lo, loIncl = a.loIncl, hi = hi, hiIncl = hiIncl)
    }
}
