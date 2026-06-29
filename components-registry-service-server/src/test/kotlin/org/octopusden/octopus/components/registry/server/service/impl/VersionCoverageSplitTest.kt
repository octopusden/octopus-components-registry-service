package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [VersionCoverageSplit] — the write-time auto-split helper (ADR-018 refinement b).
 * Pure string-in / string-out, no Spring/DB.
 */
class VersionCoverageSplitTest {

    @Test
    @DisplayName("canonical: override [2,3) inside presence [1,10) → [1,2),[2,3),[3,10)")
    fun `internal override splits into three`() {
        assertEquals(
            listOf("[1,2)", "[2,3)", "[3,10)"),
            VersionCoverageSplit.split("[1,10)", "[2,3)"),
        )
    }

    @Test
    @DisplayName("open-upper override [2,) inside presence [1,) → [1,2),[2,)")
    fun `open upper override splits at its floor`() {
        assertEquals(
            listOf("[1,2)", "[2,)"),
            VersionCoverageSplit.split("[1,)", "[2,)"),
        )
    }

    @Test
    @DisplayName("override sharing the presence lower bound only splits at the interior edge")
    fun `override at lower bound splits once`() {
        // [1,5)'s lower edge 1 is NOT strictly inside [1,10); only 5 is interior.
        assertEquals(
            listOf("[1,5)", "[5,10)"),
            VersionCoverageSplit.split("[1,10)", "[1,5)"),
        )
    }

    @Test
    @DisplayName("override equal to the presence range introduces no interior edge → unchanged")
    fun `override equal to presence is no-op`() {
        assertEquals(listOf("[1,10)"), VersionCoverageSplit.split("[1,10)", "[1,10)"))
    }

    @Test
    @DisplayName("override disjoint from presence → unchanged")
    fun `disjoint override is no-op`() {
        assertEquals(listOf("[1,2)"), VersionCoverageSplit.split("[1,2)", "[5,6)"))
    }

    @Test
    @DisplayName("override wider than presence (edges outside) → unchanged")
    fun `wider override is no-op`() {
        assertEquals(listOf("[2,3)"), VersionCoverageSplit.split("[2,3)", "[1,10)"))
    }

    @Test
    @DisplayName("idempotent: re-splitting an already-aligned sub-range is a no-op")
    fun `idempotent on aligned ranges`() {
        // After the first split [1,10)→[1,2),[2,3),[3,10), applying the same override again
        // to each sub-range introduces no new interior edge.
        assertEquals(listOf("[1,2)"), VersionCoverageSplit.split("[1,2)", "[2,3)"))
        assertEquals(listOf("[2,3)"), VersionCoverageSplit.split("[2,3)", "[2,3)"))
        assertEquals(listOf("[3,10)"), VersionCoverageSplit.split("[3,10)", "[2,3)"))
    }

    @Test
    @DisplayName("inclusive upper bound preserved on the final piece")
    fun `preserves outer inclusivity`() {
        assertEquals(listOf("[1,5)", "[5,10]"), VersionCoverageSplit.split("[1,10]", "[5,)"))
    }

    @Test
    @DisplayName("override with inclusive upper [5,8] → 8 joins the override/left piece: [1,5),[5,8],(8,10)")
    fun `inclusive upper override keeps its endpoint`() {
        assertEquals(
            listOf("[1,5)", "[5,8]", "(8,10)"),
            VersionCoverageSplit.split("[1,10)", "[5,8]"),
        )
    }

    @Test
    @DisplayName("override with open lower (2,3) → 2 joins the left piece: [1,2],(2,3),[3,10)")
    fun `open lower override keeps endpoint on left`() {
        assertEquals(
            listOf("[1,2]", "(2,3)", "[3,10)"),
            VersionCoverageSplit.split("[1,10)", "(2,3)"),
        )
    }

    @Test
    @DisplayName("override (2,3] → both endpoints handled: [1,2],(2,3],(3,10)")
    fun `open lower inclusive upper override`() {
        assertEquals(
            listOf("[1,2]", "(2,3]", "(3,10)"),
            VersionCoverageSplit.split("[1,10)", "(2,3]"),
        )
    }

    @Test
    @DisplayName("the middle sub-range is exactly the override range for every endpoint shape")
    fun `middle sub-range equals override`() {
        // For each shape the override range must appear verbatim among the split parts.
        for (ov in listOf("[2,3)", "(2,3)", "[2,3]", "(2,3]")) {
            val parts = VersionCoverageSplit.split("[1,10)", ov)!!
            assertEquals(true, ov in parts, "override $ov must be one of the split sub-ranges: $parts")
        }
    }

    @Test
    @DisplayName("composite or malformed presence range → null (left untouched)")
    fun `composite presence returns null`() {
        assertNull(VersionCoverageSplit.split("(,1.0),[2.0,)", "[3.0,4.0)"))
        assertNull(VersionCoverageSplit.split("[1,10)", "(,1.0),[2.0,)"))
    }
}
