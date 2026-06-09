package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pure unit test for the numeric-aware version ordering used by
 * /meta/java-versions and /meta/maven-versions. The key property is that
 * multi-digit / multi-segment versions sort numerically, not lexicographically
 * (a lexicographic sort would place "11" before "9" and "3.3.9" before "9").
 */
@Tag("unit")
class VersionComparatorTest {
    @Test
    @DisplayName("VERSION_COMPARATOR orders versions numerically by dot-segment, not lexicographically")
    fun `orders versions numerically not lexicographically`() {
        val input = listOf("11", "1.8", "9", "3.3.9", "21", "3.6", "3.6.3")
        val sorted = input.sortedWith(ComponentControllerV4.VERSION_COMPARATOR)
        assertEquals(
            listOf("1.8", "3.3.9", "3.6", "3.6.3", "9", "11", "21"),
            sorted,
        )
    }

    @Test
    @DisplayName("VERSION_COMPARATOR sorts the default Maven option list ascending")
    fun `sorts the default maven list ascending`() {
        val input = listOf("3.9", "2.2.1", "3.6.3", "3", "3.8", "3.3.9", "3.6")
        val sorted = input.sortedWith(ComponentControllerV4.VERSION_COMPARATOR)
        assertEquals(
            listOf("2.2.1", "3", "3.3.9", "3.6", "3.6.3", "3.8", "3.9"),
            sorted,
        )
    }
}
