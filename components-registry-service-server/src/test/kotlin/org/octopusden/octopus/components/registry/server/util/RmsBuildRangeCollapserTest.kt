package org.octopusden.octopus.components.registry.server.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.util.RmsBuildRangeCollapser.Build

class RmsBuildRangeCollapserTest {
    private val intParse: (String) -> Int = { it.toInt() }

    @Test
    @DisplayName("no builds collapse to no runs")
    fun `empty input produces no runs`() {
        val result = RmsBuildRangeCollapser.collapse(emptyList(), intParse)
        assertEquals(emptyList<RmsBuildRangeCollapser.Run>(), result.runs)
        assertEquals(false, result.hasUnparseableVersion)
    }

    @Test
    @DisplayName("a single build produces one open-ended run")
    fun `single build produces one open-ended run`() {
        val result = RmsBuildRangeCollapser.collapse(listOf(Build("2", "17")), intParse)
        assertEquals(listOf(RmsBuildRangeCollapser.Run("[2,)", "17")), result.runs)
        assertEquals(false, result.hasUnparseableVersion)
    }

    @Test
    @DisplayName("a differing-value build ends the current run before it; only the last run is open-ended")
    fun `two builds with different values produce two runs`() {
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("2", "17"), Build("3", "21")),
            intParse,
        )
        assertEquals(
            listOf(
                RmsBuildRangeCollapser.Run("[2,3)", "17"),
                RmsBuildRangeCollapser.Run("[3,)", "21"),
            ),
            result.runs,
        )
    }

    @Test
    @DisplayName("a run bridges a stretch that was never built at all")
    fun `same-value builds separated by an unbuilt gap merge into one run`() {
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("5", "17"), Build("10", "17")),
            intParse,
        )
        assertEquals(listOf(RmsBuildRangeCollapser.Run("[5,)", "17")), result.runs)
    }

    @Test
    @DisplayName("a null-value build ends the current run and starts no new one")
    fun `a trailing null build closes the run with no upper bound left open`() {
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("1", "17"), Build("2", "17"), Build("3", null)),
            intParse,
        )
        assertEquals(listOf(RmsBuildRangeCollapser.Run("[1,3)", "17")), result.runs)
    }

    @Test
    @DisplayName("a null build between two agreeing runs breaks them apart, unlike an unbuilt gap")
    fun `a null observation splits a run even when the value resumes unchanged`() {
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("1", "17"), Build("2", "17"), Build("3", null), Build("4", "17"), Build("5", "17")),
            intParse,
        )
        assertEquals(
            listOf(
                RmsBuildRangeCollapser.Run("[1,3)", "17"),
                RmsBuildRangeCollapser.Run("[4,)", "17"),
            ),
            result.runs,
        )
    }

    @Test
    @DisplayName("builds are sorted by parsed version before the walk, regardless of input order")
    fun `out-of-order input is sorted before collapsing`() {
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("3", "21"), Build("1", "17"), Build("2", "17")),
            intParse,
        )
        assertEquals(
            listOf(
                RmsBuildRangeCollapser.Run("[1,3)", "17"),
                RmsBuildRangeCollapser.Run("[3,)", "21"),
            ),
            result.runs,
        )
    }

    @Test
    @DisplayName("a build whose version cannot be parsed is excluded and flags hasUnparseableVersion")
    fun `a build whose version cannot be parsed is excluded and flags hasUnparseableVersion`() {
        val parseDigitsOnly: (String) -> Int = { version ->
            version.toIntOrNull() ?: throw NumberFormatException("not a plain integer: $version")
        }
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("1", "17"), Build("not-a-version", "99"), Build("2", "17")),
            parseDigitsOnly,
        )
        assertEquals(listOf(RmsBuildRangeCollapser.Run("[1,)", "17")), result.runs)
        assertEquals(true, result.hasUnparseableVersion)
    }

    @Test
    @DisplayName("valuesEqual is injected, so custom normalization can merge values the collapser would otherwise treat as different")
    fun `custom valuesEqual merges values the collapser would otherwise treat as different`() {
        val javaEight: (String, String) -> Boolean = { a, b -> (a == "1.8" || a == "8") && (b == "1.8" || b == "8") }
        val result = RmsBuildRangeCollapser.collapse(
            listOf(Build("1", "1.8"), Build("2", "8")),
            intParse,
            valuesEqual = javaEight,
        )
        assertEquals(listOf(RmsBuildRangeCollapser.Run("[1,)", "1.8")), result.runs)
    }
}
