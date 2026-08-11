package org.octopusden.octopus.components.registry.server.service.rms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.dto.v4.ActualDisagreement
import org.octopusden.octopus.components.registry.server.dto.v4.ActualRange
import org.octopusden.octopus.components.registry.server.util.BuildRangeCollapser
import org.octopusden.octopus.components.registry.server.util.JavaVersionComparator
import org.octopusden.octopus.components.registry.server.util.MavenVersionComparator
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

class RegisteredBuildParametersMapperTest {
    private val intCompare: (String, String) -> Int = { a, b -> a.toInt().compareTo(b.toInt()) }
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private val numericVersionFactory = NumericVersionFactory(versionNames)

    @Test
    @DisplayName("rollup is the maximum value across all ranges")
    fun `rollup returns the maximum value`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,2)", "8"), BuildRangeCollapser.Run("[2,)", "17"))
        assertEquals("17", RegisteredBuildParametersMapper.rollup(ranges, JavaVersionComparator::compare))
    }

    @Test
    @DisplayName("rollup treats equivalent Java spellings as one value, not two")
    fun `rollup normalizes Java spelling before finding the maximum`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,2)", "1.8"), BuildRangeCollapser.Run("[2,)", "8"))
        val result = RegisteredBuildParametersMapper.rollup(ranges, JavaVersionComparator::compare)
        assertEquals(
            8,
            result?.let {
                JavaVersionComparator.majorVersion(it)
            },
            "1.8 and 8 must collapse to one major-version-8 maximum, whichever spelling is reported",
        )
    }

    @Test
    @DisplayName("rollup reflects a numerically-higher-but-superseded line, not the current (most recent) one")
    fun `rollup reflects the highest value ever recorded, even from a superseded line`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,2)", "21"), BuildRangeCollapser.Run("[2,)", "11"))
        assertEquals("21", RegisteredBuildParametersMapper.rollup(ranges, JavaVersionComparator::compare))
    }

    @Test
    @DisplayName("effectiveJavaVersion prefers RMS's rollup over the configured BASE value")
    fun `effectiveJavaVersion prefers RMS rollup`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,)", "21"))
        assertEquals("21", RegisteredBuildParametersMapper.effectiveJavaVersion("8", ranges))
    }

    @Test
    @DisplayName("effectiveJavaVersion falls back to the configured BASE value when RMS has no ranges")
    fun `effectiveJavaVersion falls back to BASE value`() {
        assertEquals("8", RegisteredBuildParametersMapper.effectiveJavaVersion("8", emptyList()))
    }

    @Test
    @DisplayName("effectiveJavaVersion is null when RMS has no ranges and the BASE value is blank")
    fun `effectiveJavaVersion is null when both sources are absent`() {
        assertNull(RegisteredBuildParametersMapper.effectiveJavaVersion("   ", emptyList()))
        assertNull(RegisteredBuildParametersMapper.effectiveJavaVersion(null, emptyList()))
    }

    @Test
    @DisplayName("actualValueAt returns the value of the range containing the target version")
    fun `actualValueAt finds the containing range`() {
        val ranges = listOf(BuildRangeCollapser.Run("(1.0,1.3]", "17"), BuildRangeCollapser.Run("(1.3,)", "21"))
        val target = numericVersionFactory.create("2.0")
        assertEquals("21", RegisteredBuildParametersMapper.actualValueAt(ranges, target, versionRangeFactory))
    }

    @Test
    @DisplayName("actualValueAt is null when no range contains the target version")
    fun `actualValueAt is null when nothing matches`() {
        val ranges = listOf(BuildRangeCollapser.Run("(1.0,1.3]", "17"))
        val target = numericVersionFactory.create("5.0")
        assertNull(RegisteredBuildParametersMapper.actualValueAt(ranges, target, versionRangeFactory))
    }

    @Test
    @DisplayName("actualValueAt is null for an empty range list")
    fun `actualValueAt is null when there are no ranges`() {
        val target = numericVersionFactory.create("1.0")
        assertNull(RegisteredBuildParametersMapper.actualValueAt(emptyList(), target, versionRangeFactory))
    }

    @Test
    @DisplayName("Maven's LATEST always wins the rollup")
    fun `rollup lets LATEST win over any numbered version`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,2)", "3.3.9"), BuildRangeCollapser.Run("[2,)", "LATEST"))
        assertEquals("LATEST", RegisteredBuildParametersMapper.rollup(ranges, MavenVersionComparator::compare))
    }

    @Test
    @DisplayName("rollup of no ranges is null")
    fun `rollup of an empty list is null`() {
        assertNull(RegisteredBuildParametersMapper.rollup(emptyList(), JavaVersionComparator::compare))
    }

    @Test
    @DisplayName("an unparseable value never breaks the rollup — it's skipped rather than thrown")
    fun `rollup skips a value the comparator cannot parse`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,2)", "17-ea"), BuildRangeCollapser.Run("[2,)", "11"))
        assertEquals("11", RegisteredBuildParametersMapper.rollup(ranges, JavaVersionComparator::compare))
    }

    @Test
    @DisplayName("rollup of only unparseable values is null, not a thrown exception")
    fun `rollup of only unparseable values is null`() {
        val ranges = listOf(BuildRangeCollapser.Run("[1,)", "17-ea"))
        assertNull(RegisteredBuildParametersMapper.rollup(ranges, JavaVersionComparator::compare))
    }

    @Test
    @DisplayName("a matching configured row produces no warning")
    fun `a matching row produces no warning`() {
        val rows = listOf(ActualRange("[1,3)", "17"))
        val actual = listOf(BuildRangeCollapser.Run("[1,3)", "17"))
        assertTrue(RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare).isEmpty())
    }

    @Test
    @DisplayName("a disagreeing configured row names the intersecting sub-range and ACTUAL's value")
    fun `a disagreeing row names the intersecting sub-range and ACTUAL value`() {
        val rows = listOf(ActualRange("[1,4)", "11"))
        val actual = listOf(BuildRangeCollapser.Run("[3,)", "17"))
        assertEquals(
            listOf(ActualDisagreement("[3,4)", "17")),
            RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare),
        )
    }

    @Test
    @DisplayName("a non-intersecting ACTUAL range produces no warning")
    fun `a non-intersecting range produces no warning`() {
        val rows = listOf(ActualRange("[1,2)", "11"))
        val actual = listOf(BuildRangeCollapser.Run("[5,)", "17"))
        assertTrue(RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare).isEmpty())
    }

    @Test
    @DisplayName("a row intersecting multiple disagreeing ACTUAL ranges produces multiple warnings")
    fun `a row spanning multiple disagreeing ranges produces multiple warnings`() {
        val rows = listOf(ActualRange("[1,10)", "11"))
        val actual = listOf(BuildRangeCollapser.Run("[2,5)", "17"), BuildRangeCollapser.Run("[5,)", "21"))
        assertEquals(
            listOf(ActualDisagreement("[2,5)", "17"), ActualDisagreement("[5,10)", "21")),
            RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare),
        )
    }

    @Test
    @DisplayName("a composite configured row is checked segment by segment, one warning per disagreeing segment")
    fun `a composite row produces one warning per disagreeing segment`() {
        val rows = listOf(ActualRange("[1,2),[5,6)", "11"))
        val actual = listOf(BuildRangeCollapser.Run("[1,2)", "17"), BuildRangeCollapser.Run("[5,6)", "11"))
        assertEquals(
            listOf(ActualDisagreement("[1,2)", "17")),
            RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare),
        )
    }

    @Test
    @DisplayName("two independent configured rows are each checked against ACTUAL on their own")
    fun `two configured rows are each checked independently`() {
        val rows = listOf(ActualRange("[1,3)", "17"), ActualRange("[3,5)", "11"))
        val actual = listOf(BuildRangeCollapser.Run("[1,)", "17"))
        assertEquals(
            listOf(ActualDisagreement("[3,5)", "17")),
            RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare),
        )
    }

    @Test
    @DisplayName("a row spanning two ACTUAL ranges warns only for the one it actually disagrees with")
    fun `a row agreeing with one ACTUAL range and disagreeing with another warns only once`() {
        val rows = listOf(ActualRange("[1,10)", "17"))
        val actual = listOf(BuildRangeCollapser.Run("[1,5)", "17"), BuildRangeCollapser.Run("[5,)", "21"))
        assertEquals(
            listOf(ActualDisagreement("[5,10)", "21")),
            RegisteredBuildParametersMapper.warnings(rows, actual, String::equals, intCompare),
        )
    }
}
