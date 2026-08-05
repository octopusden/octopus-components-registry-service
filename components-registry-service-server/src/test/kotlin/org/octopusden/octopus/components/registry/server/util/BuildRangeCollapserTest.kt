package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser.Build

class BuildRangeCollapserTest {
    @Test
    @DisplayName("no builds collapse to no runs")
    fun `empty input produces no runs`() {
        assertEquals(emptyList<BuildRangeCollapser.Run>(), BuildRangeCollapser.collapse(emptyList()))
    }

    @Test
    @DisplayName("a single build produces one open-ended run")
    fun `single build produces one open-ended run`() {
        val result = BuildRangeCollapser.collapse(listOf(Build("2", "17")))
        assertEquals(listOf(BuildRangeCollapser.Run("[2,)", "17")), result)
    }

    @Test
    @DisplayName("a differing-value build ends the current run before it; only the last run is open-ended")
    fun `two builds with different values produce two runs`() {
        // Input is ascending, matching RMS's guaranteed order.
        val result = BuildRangeCollapser.collapse(listOf(Build("2", "17"), Build("3", "21")))
        assertEquals(
            listOf(
                BuildRangeCollapser.Run("[2,3)", "17"),
                BuildRangeCollapser.Run("[3,)", "21"),
            ),
            result,
        )
    }

    @Test
    @DisplayName("a run bridges a stretch that was never built at all")
    fun `same-value builds separated by an unbuilt gap merge into one run`() {
        val result = BuildRangeCollapser.collapse(listOf(Build("5", "17"), Build("10", "17")))
        assertEquals(listOf(BuildRangeCollapser.Run("[5,)", "17")), result)
    }

    @Test
    @DisplayName("a null-value build ends the current run and starts no new one")
    fun `a trailing null build closes the run with no upper bound left open`() {
        val result = BuildRangeCollapser.collapse(listOf(Build("1", "17"), Build("2", "17"), Build("3", null)))
        assertEquals(listOf(BuildRangeCollapser.Run("[1,3)", "17")), result)
    }

    @Test
    @DisplayName("a null build between two agreeing runs breaks them apart, unlike an unbuilt gap")
    fun `a null observation splits a run even when the value resumes unchanged`() {
        val result = BuildRangeCollapser.collapse(
            listOf(Build("1", "17"), Build("2", "17"), Build("3", null), Build("4", "17"), Build("5", "17")),
        )
        assertEquals(
            listOf(
                BuildRangeCollapser.Run("[1,3)", "17"),
                BuildRangeCollapser.Run("[4,)", "17"),
            ),
            result,
        )
    }

    @Test
    @DisplayName("valuesEqual is injected, so custom normalization can merge values the collapser would otherwise treat as different")
    fun `custom valuesEqual merges values the collapser would otherwise treat as different`() {
        val javaEight: (String, String) -> Boolean = { a, b -> (a == "1.8" || a == "8") && (b == "1.8" || b == "8") }
        val result = BuildRangeCollapser.collapse(
            listOf(Build("1", "1.8"), Build("2", "8")),
            valuesEqual = javaEight,
        )
        assertEquals(listOf(BuildRangeCollapser.Run("[1,)", "1.8")), result)
    }
}
