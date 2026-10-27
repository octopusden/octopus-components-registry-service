package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VersionRangeIntersectorTest {
    private val intCompare: (String, String) -> Int = { a, b -> a.toInt().compareTo(b.toInt()) }

    @Test
    @DisplayName("two disjoint closed ranges do not intersect")
    fun `disjoint ranges return no sub-ranges`() {
        assertEquals(emptyList<String>(), VersionRangeIntersector.intersect("[1,2)", "[3,4)", intCompare))
    }

    @Test
    @DisplayName("overlapping closed ranges intersect at the tighter bounds")
    fun `overlapping ranges intersect at the tighter bounds`() {
        assertEquals(listOf("[2,3)"), VersionRangeIntersector.intersect("[1,3)", "[2,4)", intCompare))
    }

    @Test
    @DisplayName("an open-ended range intersecting a closed range is bounded by the closed range's finite side")
    fun `open-ended range intersects a closed range at the closed side`() {
        assertEquals(listOf("[2,3)"), VersionRangeIntersector.intersect("[2,)", "[1,3)", intCompare))
    }

    @Test
    @DisplayName("two open-ended ranges intersect at the higher of their two starts, still open-ended")
    fun `two open-ended ranges intersect at the higher start`() {
        assertEquals(listOf("[3,)"), VersionRangeIntersector.intersect("[1,)", "[3,)", intCompare))
    }

    @Test
    @DisplayName("ALL_VERSIONS intersected with anything yields that other range unchanged")
    fun `all versions intersected with a range yields that range`() {
        assertEquals(listOf("[1,2)"), VersionRangeIntersector.intersect("(,0),[0,)", "[1,2)", intCompare))
    }

    @Test
    @DisplayName("touching half-open ranges (one ends where the other starts) do not intersect")
    fun `touching half-open ranges do not intersect`() {
        assertEquals(emptyList<String>(), VersionRangeIntersector.intersect("[1,2)", "[2,3)", intCompare))
    }

    @Test
    @DisplayName("identical ranges intersect as themselves")
    fun `identical ranges intersect as themselves`() {
        assertEquals(listOf("[1,3)"), VersionRangeIntersector.intersect("[1,3)", "[1,3)", intCompare))
    }

    @Test
    @DisplayName("ALL_VERSIONS intersected with itself is ALL_VERSIONS")
    fun `all versions intersected with all versions is all versions`() {
        assertEquals(listOf("(,0),[0,)"), VersionRangeIntersector.intersect("(,0),[0,)", "(,0),[0,)", intCompare))
    }

    @Test
    @DisplayName("a composite range on one side intersects each of its segments against the other side independently")
    fun `a composite range intersects segment by segment`() {
        assertEquals(listOf("[1,2)", "[5,10)"), VersionRangeIntersector.intersect("[1,2),[5,)", "[1,10)", intCompare))
        assertEquals(listOf("[1,2)", "[5,10)"), VersionRangeIntersector.intersect("[1,10)", "[1,2),[5,)", intCompare))
    }

    @Test
    @DisplayName("a composite range on both sides intersects every pair of segments")
    fun `composite on both sides intersects every segment pair`() {
        assertEquals(
            listOf("[2,3)", "[6,7)"),
            VersionRangeIntersector.intersect("[1,3),[6,9)", "[2,4),[5,7)", intCompare),
        )
    }

    @Test
    @DisplayName("a malformed range string returns no sub-ranges rather than throwing")
    fun `a malformed range returns no sub-ranges`() {
        assertEquals(emptyList<String>(), VersionRangeIntersector.intersect("not-a-range", "[1,10)", intCompare))
    }

    @Test
    @DisplayName("two closed boundaries meeting at exactly one point intersect as that single version, not nothing")
    fun `closed boundaries meeting at one point intersect as a single version`() {
        assertEquals(listOf("[3]"), VersionRangeIntersector.intersect("[1,3]", "[3,5)", intCompare))
    }

    @Test
    @DisplayName("a single-segment range and ALL_VERSIONS are fully parseable")
    fun `simple ranges are fully parseable`() {
        assertTrue(VersionRangeIntersector.isFullyParseable("[1,3)"))
        assertTrue(VersionRangeIntersector.isFullyParseable("(,0),[0,)"))
    }

    @Test
    @DisplayName("a clean composite range is fully parseable")
    fun `a clean composite range is fully parseable`() {
        assertTrue(VersionRangeIntersector.isFullyParseable("[1,2),[5,)"))
    }

    @Test
    @DisplayName("a malformed range is not fully parseable")
    fun `a malformed range is not fully parseable`() {
        assertFalse(VersionRangeIntersector.isFullyParseable("not-a-range"))
    }

    @Test
    @DisplayName("a composite range with one bad segment is not fully parseable, even though the other segment is fine")
    fun `a partially malformed composite range is not fully parseable`() {
        assertFalse(VersionRangeIntersector.isFullyParseable("[1,2),garbage,[5,)"))
    }
}
