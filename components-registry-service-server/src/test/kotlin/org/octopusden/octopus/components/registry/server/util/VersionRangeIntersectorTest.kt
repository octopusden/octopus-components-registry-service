package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VersionRangeIntersectorTest {
    private val intCompare: (String, String) -> Int = { a, b -> a.toInt().compareTo(b.toInt()) }

    @Test
    @DisplayName("two disjoint closed ranges do not intersect")
    fun `disjoint ranges return null`() {
        assertNull(VersionRangeIntersector.intersect("[1,2)", "[3,4)", intCompare))
    }

    @Test
    @DisplayName("overlapping closed ranges intersect at the tighter bounds")
    fun `overlapping ranges intersect at the tighter bounds`() {
        assertEquals("[2,3)", VersionRangeIntersector.intersect("[1,3)", "[2,4)", intCompare))
    }

    @Test
    @DisplayName("an open-ended range intersecting a closed range is bounded by the closed range's finite side")
    fun `open-ended range intersects a closed range at the closed side`() {
        assertEquals("[2,3)", VersionRangeIntersector.intersect("[2,)", "[1,3)", intCompare))
    }

    @Test
    @DisplayName("two open-ended ranges intersect at the higher of their two starts, still open-ended")
    fun `two open-ended ranges intersect at the higher start`() {
        assertEquals("[3,)", VersionRangeIntersector.intersect("[1,)", "[3,)", intCompare))
    }

    @Test
    @DisplayName("ALL_VERSIONS intersected with anything yields that other range unchanged")
    fun `all versions intersected with a range yields that range`() {
        assertEquals("[1,2)", VersionRangeIntersector.intersect("(,0),[0,)", "[1,2)", intCompare))
    }

    @Test
    @DisplayName("touching half-open ranges (one ends where the other starts) do not intersect")
    fun `touching half-open ranges do not intersect`() {
        assertNull(VersionRangeIntersector.intersect("[1,2)", "[2,3)", intCompare))
    }

    @Test
    @DisplayName("identical ranges intersect as themselves")
    fun `identical ranges intersect as themselves`() {
        assertEquals("[1,3)", VersionRangeIntersector.intersect("[1,3)", "[1,3)", intCompare))
    }

    @Test
    @DisplayName("ALL_VERSIONS intersected with itself is ALL_VERSIONS")
    fun `all versions intersected with all versions is all versions`() {
        assertEquals("(,0),[0,)", VersionRangeIntersector.intersect("(,0),[0,)", "(,0),[0,)", intCompare))
    }

    @Test
    @DisplayName("a composite (multi-segment) range on either side is unsupported and returns null")
    fun `a composite range returns null`() {
        assertNull(VersionRangeIntersector.intersect("[1,2),[5,)", "[1,10)", intCompare))
        assertNull(VersionRangeIntersector.intersect("[1,10)", "[1,2),[5,)", intCompare))
    }

    @Test
    @DisplayName("a malformed range string returns null rather than throwing")
    fun `a malformed range returns null`() {
        assertNull(VersionRangeIntersector.intersect("not-a-range", "[1,10)", intCompare))
    }

    @Test
    @DisplayName("two closed boundaries meeting at exactly one point intersect as that single version, not null")
    fun `closed boundaries meeting at one point intersect as a single version`() {
        assertEquals("[3]", VersionRangeIntersector.intersect("[1,3]", "[3,5)", intCompare))
    }
}
