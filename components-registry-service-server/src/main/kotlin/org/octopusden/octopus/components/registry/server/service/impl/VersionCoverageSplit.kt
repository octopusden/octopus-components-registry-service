package org.octopusden.octopus.components.registry.server.service.impl

import org.apache.maven.artifact.versioning.DefaultArtifactVersion

/**
 * Write-time auto-split of supported-coverage ranges (ADR-018 refinement (b)).
 *
 * Migrated declared ranges are breakpoint-aligned: every attribute is constant across each
 * `RANGE_PRESENCE` range, so range-view enumeration emits one config per presence row. A v4 write
 * that adds a per-attribute override whose range introduces a boundary **inside** a covering
 * presence range would break that invariant — the enumerated view of the covering range would no
 * longer have constant resolved values. To keep reads trivial we **auto-split** the covering
 * `RANGE_PRESENCE` at the override's internal edges on write, so each enumerated range again has
 * constant values. (The rejected alternative — partition by override edges at enumerate time —
 * pushes complexity onto the hot read path.)
 *
 * These helpers are pure (string-in / string-out, Maven version comparison only) so they are unit
 * testable without a Spring/DB context; [ComponentManagementServiceImpl] wires them into the
 * field-override write path.
 */
internal object VersionCoverageSplit {

    /** A single half-open/closed Maven version interval, e.g. `[1.0,2.0)`, `[2.0,)`, `(,3.0]`. */
    internal data class Segment(
        val lo: String?,
        val loIncl: Boolean,
        val hi: String?,
        val hiIncl: Boolean,
    )

    private val SIMPLE_SEGMENT_PATTERN = Regex("^([\\[(])([^,]*),([^,]*)([\\])])$")

    internal fun normalize(range: String): String = range.trim().replace(Regex("\\s+"), "")

    /**
     * Parse a single-segment range string into a [Segment], or `null` for a composite / malformed
     * range (e.g. `(,1.0),[2.0,)` or `[1.0]`). A null bound denotes an open (unbounded) side.
     */
    internal fun parseSegment(range: String): Segment? {
        val m = SIMPLE_SEGMENT_PATTERN.matchEntire(normalize(range)) ?: return null
        val (open, loStr, hiStr, close) = m.destructured
        if (loStr.any { it in "()[]" } || hiStr.any { it in "()[]" }) return null
        return Segment(
            lo = loStr.trim().takeIf { it.isNotEmpty() },
            loIncl = open == "[",
            hi = hiStr.trim().takeIf { it.isNotEmpty() },
            hiIncl = close == "]",
        )
    }

    /** Render a [Segment] back to canonical Maven-range text (`[1.0,2.0)`). */
    internal fun render(seg: Segment): String =
        buildString {
            append(if (seg.loIncl) '[' else '(')
            append(seg.lo ?: "")
            append(',')
            append(seg.hi ?: "")
            append(if (seg.hiIncl) ']' else ')')
        }

    private fun cmp(a: String, b: String): Int = DefaultArtifactVersion(a).compareTo(DefaultArtifactVersion(b))

    /** True iff finite [point] lies STRICTLY inside [seg] (excluding both endpoints). */
    private fun strictlyInside(seg: Segment, point: String): Boolean {
        val aboveLo = seg.lo == null || cmp(point, seg.lo) > 0
        val belowHi = seg.hi == null || cmp(point, seg.hi) < 0
        return aboveLo && belowHi
    }

    /**
     * Split [presenceRange] at every finite endpoint of [overrideRange] that falls strictly inside
     * it, returning the ordered list of resulting sub-range strings. Returns a single-element list
     * (the normalized original) when no override edge is interior. Returns `null` when either range
     * is not a single parseable segment — composite / malformed presence rows are left untouched
     * (the caller skips them).
     *
     * The split is half-open at the introduced edges: splitting `[1,10)` at `2` and `3` (override
     * `[2,3)`) yields `[1,2)`, `[2,3)`, `[3,10)`; the outer endpoints keep the original range's
     * inclusivity. Idempotent: re-splitting an already-aligned set of ranges is a no-op.
     */
    internal fun split(
        presenceRange: String,
        overrideRange: String,
    ): List<String>? {
        val presence = parseSegment(presenceRange) ?: return null
        val override = parseSegment(overrideRange) ?: return null

        // The override's finite endpoints are the candidate internal breakpoints.
        val edges = listOfNotNull(override.lo, override.hi)
            .filter { strictlyInside(presence, it) }
            .distinct()
            .sortedWith { a, b -> cmp(a, b) }

        if (edges.isEmpty()) return listOf(render(presence))

        val result = mutableListOf<Segment>()
        var curLo = presence.lo
        var curLoIncl = presence.loIncl
        for (edge in edges) {
            // [curLo, edge) — upper-exclusive at the introduced breakpoint.
            result += Segment(lo = curLo, loIncl = curLoIncl, hi = edge, hiIncl = false)
            curLo = edge
            curLoIncl = true // the breakpoint belongs to the upper piece
        }
        // Final piece runs to the presence range's original upper bound / inclusivity.
        result += Segment(lo = curLo, loIncl = curLoIncl, hi = presence.hi, hiIncl = presence.hiIncl)
        return result.map { render(it) }
    }
}
