package org.octopusden.octopus.components.registry.server.util

/**
 * Computes the intersecting sub-ranges of two Maven-syntax range strings — unlike
 * [VersionRangePartition], which only checks/reports whether ranges overlap, this returns the
 * overlap itself. Either side may be composite (multi-segment, e.g. `[1,2),[5,)`); every segment on
 * one side is intersected against every segment on the other, so a composite input can yield several
 * disjoint sub-ranges, not just one. [intersect] silently drops any piece it can't parse —
 * [isFullyParseable] is the fail-closed check for a caller that needs to know the whole range,
 * not just the parseable part of it, was evaluated.
 */
object VersionRangeIntersector {
    fun intersect(
        a: String,
        b: String,
        compare: (String, String) -> Int,
    ): List<String> =
        VersionRangePartition.toSegments(a).flatMap { segA ->
            VersionRangePartition.toSegments(b).mapNotNull { segB -> intersectSegments(segA, segB, compare) }
        }

    /** `true` iff every piece of [range] parses cleanly — no dropped or malformed segment. */
    fun isFullyParseable(range: String): Boolean = VersionRangePartition.toSegmentsOrNull(range) != null

    private fun intersectSegments(
        segA: VersionRangePartition.Segment,
        segB: VersionRangePartition.Segment,
        compare: (String, String) -> Int,
    ): String? {
        val (lo, loIncl) = maxLower(segA.lo, segA.loIncl, segB.lo, segB.loIncl, compare)
        val (hi, hiIncl) = minUpper(segA.hi, segA.hiIncl, segB.hi, segB.hiIncl, compare)

        if (lo != null && hi != null && isEmpty(compare(lo, hi), loIncl, hiIncl)) return null
        return VersionRangePartition.render(VersionRangePartition.Segment(lo, loIncl, hi, hiIncl))
    }

    /** `lo..hi` (given how each bound compares and whether each is inclusive) contains no version at all. */
    private fun isEmpty(
        loHiCompare: Int,
        loIncl: Boolean,
        hiIncl: Boolean,
    ): Boolean = loHiCompare > 0 || (loHiCompare == 0 && !(loIncl && hiIncl))

    private fun maxLower(
        loA: String?,
        loInclA: Boolean,
        loB: String?,
        loInclB: Boolean,
        compare: (String, String) -> Int,
    ): Pair<String?, Boolean> =
        when {
            loA == null -> loB to loInclB
            loB == null -> loA to loInclA
            else ->
                when {
                    compare(loA, loB) > 0 -> loA to loInclA
                    compare(loA, loB) < 0 -> loB to loInclB
                    else -> loA to (loInclA && loInclB)
                }
        }

    private fun minUpper(
        hiA: String?,
        hiInclA: Boolean,
        hiB: String?,
        hiInclB: Boolean,
        compare: (String, String) -> Int,
    ): Pair<String?, Boolean> =
        when {
            hiA == null -> hiB to hiInclB
            hiB == null -> hiA to hiInclA
            else ->
                when {
                    compare(hiA, hiB) < 0 -> hiA to hiInclA
                    compare(hiA, hiB) > 0 -> hiB to hiInclB
                    else -> hiA to (hiInclA && hiInclB)
                }
        }
}
