package org.octopusden.octopus.components.registry.server.mapper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPathPreview
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewCoverage
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewRequest
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewResponse
import org.octopusden.octopus.components.registry.server.dto.v4.DistributionPreviewSource
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpsertRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MarkerChildrenPayload
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.util.UUID

/**
 * SYS-107: distribution preview over a candidate override set, evaluated with real
 * version factories, plus the shared containment / coverage helpers the resolver uses.
 */
class DistributionPreviewTest {
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val rangeFactory = VersionRangeFactory(versionNames)
    private val numericFactory = NumericVersionFactory(versionNames)

    private val maven = MarkerAttributes.DISTRIBUTION_MAVEN
    private val docker = MarkerAttributes.DISTRIBUTION_DOCKER

    private fun component(
        vararg rows: Pair<String, Pair<String?, String>>,
        baseRange: String = ALL_VERSIONS,
    ): ComponentEntity {
        val c = ComponentEntity(id = UUID.randomUUID(), componentKey = "preview-test")
        c.configurations.add(ComponentConfigurationEntity(component = c, versionRange = baseRange, rowType = "BASE"))
        rows.forEach { (rowType, attrAndRange) ->
            c.configurations.add(
                ComponentConfigurationEntity(
                    component = c,
                    versionRange = attrAndRange.second,
                    overriddenAttribute = attrAndRange.first,
                    rowType = rowType,
                ),
            )
        }
        return c
    }

    private fun override(
        attribute: String,
        range: String,
    ) = FieldOverrideUpsertRequest(
        overriddenAttribute = attribute,
        versionRange = range,
        markerChildren = MarkerChildrenPayload(mavenArtifacts = emptyList()),
    )

    private fun preview(
        version: String,
        overrides: List<FieldOverrideUpsertRequest>? = emptyList(),
        coverage: DistributionPreviewCoverage? = DistributionPreviewCoverage(all = true),
        entity: ComponentEntity = component(),
    ): DistributionPreviewResponse =
        entity.previewDistribution(
            DistributionPreviewRequest(version = version, fieldOverrides = overrides, supportedVersions = coverage),
            rangeFactory,
            numericFactory,
        )

    private fun DistributionPreviewResponse.path(attribute: String): DistributionPathPreview = paths.single { it.attribute == attribute }

    private fun v(version: String) = numericFactory.create(version)

    // ── AC1: BASE / OVERRIDE / CONFLICT, paths independent ───────────────────

    @Test
    @DisplayName("SYS-107: no candidate override -> every distribution path is BASE, one entry per family in resolver order")
    fun `SYS-107 no override gives BASE on every distribution family`() {
        val result = preview("1.5")
        assertTrue(result.supported)
        assertEquals("1.5", result.version)
        val families = MarkerAttributes.ALL.filter { it.startsWith("distribution.") }
        assertEquals(families, result.paths.map { it.attribute })
        assertTrue(
            result.paths.map { it.attribute }.containsAll(
                listOf(
                    MarkerAttributes.DISTRIBUTION_MAVEN,
                    MarkerAttributes.DISTRIBUTION_FILE_URL,
                    MarkerAttributes.DISTRIBUTION_DOCKER,
                    MarkerAttributes.DISTRIBUTION_PACKAGES,
                    MarkerAttributes.DISTRIBUTION_GENERIC,
                ),
            ),
        )
        result.paths.forEach {
            assertEquals(DistributionPreviewSource.BASE, it.source, it.attribute)
            assertEquals(emptyList<String>(), it.matchedRanges)
            assertEquals(emptyList<String>(), it.unevaluableRanges)
        }
    }

    @Test
    @DisplayName("SYS-107: one matching override -> OVERRIDE with its range; other paths stay independent")
    fun `SYS-107 one matching override gives OVERRIDE and paths are independent`() {
        val result = preview("1.5", listOf(override(maven, "[1.0,2.0)"), override(docker, "[2.0,3.0)")))
        assertEquals(DistributionPreviewSource.OVERRIDE, result.path(maven).source)
        assertEquals(listOf("[1.0,2.0)"), result.path(maven).matchedRanges)
        assertEquals(DistributionPreviewSource.BASE, result.path(docker).source)
        assertEquals(emptyList<String>(), result.path(docker).matchedRanges)
        assertEquals(DistributionPreviewSource.BASE, result.path(MarkerAttributes.DISTRIBUTION_PACKAGES).source)
    }

    @Test
    @DisplayName("SYS-107: several matching overrides on one path -> CONFLICT listing all of them in request order")
    fun `SYS-107 several matching overrides give CONFLICT`() {
        val result = preview("1.7", listOf(override(maven, "[1.0,2.0)"), override(maven, "[1.5,3.0)"), override(maven, "[5,6)")))
        assertEquals(DistributionPreviewSource.CONFLICT, result.path(maven).source)
        assertEquals(listOf("[1.0,2.0)", "[1.5,3.0)"), result.path(maven).matchedRanges)
    }

    @Test
    @DisplayName("SYS-107: an override with an empty child list is a normal OVERRIDE")
    fun `SYS-107 empty child list is a normal OVERRIDE`() {
        val result = preview("1.5", listOf(override(maven, "[1.0,2.0)")))
        assertEquals(DistributionPreviewSource.OVERRIDE, result.path(maven).source)
    }

    @Test
    @DisplayName("SYS-107: candidate entries on non-distribution attributes are ignored")
    fun `SYS-107 non-distribution entries are ignored`() {
        val result =
            preview(
                "1.5",
                listOf(
                    FieldOverrideUpsertRequest(overriddenAttribute = "build.javaVersion", versionRange = "[1.0,2.0)", value = "11"),
                    override(MarkerAttributes.VCS_SETTINGS, "[1.0,2.0)"),
                ),
            )
        assertFalse(result.paths.any { it.attribute == "build.javaVersion" || it.attribute == MarkerAttributes.VCS_SETTINGS })
        result.paths.forEach { assertEquals(DistributionPreviewSource.BASE, it.source, it.attribute) }
    }

    // ── AC2: coverage ────────────────────────────────────────────────────────

    @Test
    @DisplayName("SYS-107: a version outside candidate coverage -> supported=false with no paths")
    fun `SYS-107 version outside coverage is unsupported`() {
        val coverage = DistributionPreviewCoverage(all = false, ranges = listOf("[1.0,2.0)"))
        val outside = preview("2.5", listOf(override(maven, "[2.0,3.0)")), coverage)
        assertFalse(outside.supported)
        assertEquals(emptyList<DistributionPathPreview>(), outside.paths)
        assertTrue(preview("1.5", coverage = coverage).supported)
    }

    @Test
    @DisplayName("SYS-107: no restriction (all=true, or no ranges) -> every version supported")
    fun `SYS-107 no restriction supports every version`() {
        assertTrue(preview("99.1", coverage = DistributionPreviewCoverage(all = true, ranges = listOf("[1.0,2.0)"))).supported)
        assertTrue(preview("99.1", coverage = DistributionPreviewCoverage(all = false, ranges = emptyList())).supported)
        assertTrue(preview("99.1", coverage = DistributionPreviewCoverage()).supported)
    }

    @Test
    @DisplayName("SYS-107: an unparseable or blank supported range counts as covering")
    fun `SYS-107 unparseable supported range counts as covering`() {
        assertTrue(preview("99.1", coverage = DistributionPreviewCoverage(ranges = listOf("[1.0,2.0)", "not-a-range"))).supported)
        assertTrue(preview("99.1", coverage = DistributionPreviewCoverage(ranges = listOf("  "))).supported)
    }

    // ── AC3: unparseable override ranges ─────────────────────────────────────

    @Test
    @DisplayName("SYS-107: an unparseable or blank override range never matches and is listed in unevaluableRanges")
    fun `SYS-107 unparseable override range is listed and never matches`() {
        val result = preview("1.5", listOf(override(maven, "not-a-range"), override(maven, "[1.0,2.0)"), override(maven, "")))
        val path = result.path(maven)
        assertEquals(DistributionPreviewSource.OVERRIDE, path.source)
        assertEquals(listOf("[1.0,2.0)"), path.matchedRanges)
        assertEquals(listOf("not-a-range", ""), path.unevaluableRanges)
        assertEquals(emptyList<String>(), result.path(docker).unevaluableRanges)
    }

    @Test
    @DisplayName("SYS-107: matchedRanges / unevaluableRanges echo each range byte-for-byte as sent")
    fun `SYS-107 ranges are echoed byte-for-byte`() {
        val result = preview("1.5", listOf(override(maven, " [1.0, 2.0) "), override(docker, " bad range ")))
        assertEquals(DistributionPreviewSource.OVERRIDE, result.path(maven).source)
        assertEquals(listOf(" [1.0, 2.0) "), result.path(maven).matchedRanges)
        assertEquals(listOf(" bad range "), result.path(docker).unevaluableRanges)
    }

    // ── AC4: qualified versions ──────────────────────────────────────────────

    @Test
    @DisplayName("SYS-107: qualified versions are ordered as at read time (1.2-0003 lies in [1.2,1.3))")
    fun `SYS-107 qualified version lies in range`() {
        val overrides = listOf(override(maven, "[1.2,1.3)"))
        assertEquals(DistributionPreviewSource.OVERRIDE, preview("1.2-0003", overrides).path(maven).source)
        assertEquals(DistributionPreviewSource.BASE, preview("1.1-0003", overrides).path(maven).source)
    }

    // ── AC5: omitted fields = saved state; explicit fields replace it ────────

    @Test
    @DisplayName("SYS-107: omitted fieldOverrides / supportedVersions use the saved MARKER rows and RANGE_PRESENCE coverage")
    fun `SYS-107 omitted fields use saved state`() {
        val saved =
            component(
                "RANGE_PRESENCE" to (null to "[1.0,2.0)"),
                "MARKER" to (maven to "[1.0,1.5)"),
                "SCALAR_OVERRIDE" to ("build.javaVersion" to "[1.0,2.0)"),
            )
        val inside = preview("1.2", overrides = null, coverage = null, entity = saved)
        assertTrue(inside.supported)
        assertEquals(DistributionPreviewSource.OVERRIDE, inside.path(maven).source)
        assertEquals(listOf("[1.0,1.5)"), inside.path(maven).matchedRanges)
        assertFalse(preview("3.0", overrides = null, coverage = null, entity = saved).supported)
    }

    @Test
    @DisplayName("SYS-107: present fieldOverrides / supportedVersions replace the saved state entirely")
    fun `SYS-107 present fields replace saved state`() {
        val saved = component("RANGE_PRESENCE" to (null to "[1.0,2.0)"), "MARKER" to (maven to "[1.0,1.5)"))
        val noOverrides = preview("1.2", overrides = emptyList(), coverage = null, entity = saved)
        assertEquals(DistributionPreviewSource.BASE, noOverrides.path(maven).source)
        assertTrue(preview("3.0", overrides = null, coverage = DistributionPreviewCoverage(all = true), entity = saved).supported)
    }

    @Test
    @DisplayName("SYS-107: a bounded base with no presence rows bounds coverage, also under candidate supportedVersions")
    fun `SYS-107 bounded base bounds coverage`() {
        val saved = component(baseRange = "[1.0,2.0)")
        assertTrue(preview("1.5", overrides = null, coverage = null, entity = saved).supported)
        assertFalse(preview("2.5", overrides = null, coverage = null, entity = saved).supported)
        // Candidate coverage replaces only the presence ranges; the saved bounded base still applies.
        assertFalse(preview("2.5", coverage = DistributionPreviewCoverage(all = true), entity = saved).supported)
        val widened = DistributionPreviewCoverage(ranges = listOf("[5.0,6.0)"))
        assertTrue(preview("5.5", coverage = widened, entity = saved).supported)
        assertTrue(preview("1.5", coverage = widened, entity = saved).supported)
        assertFalse(preview("3.0", coverage = widened, entity = saved).supported)
    }

    @Test
    @DisplayName("SYS-107: a version in the gap between two supported ranges is unsupported")
    fun `SYS-107 version in coverage gap is unsupported`() {
        val saved = component("RANGE_PRESENCE" to (null to "[1.0,2.0)"), "RANGE_PRESENCE" to (null to "[3.0,4.0)"))
        assertFalse(preview("2.5", overrides = null, coverage = null, entity = saved).supported)
        assertTrue(preview("3.5", overrides = null, coverage = null, entity = saved).supported)
        val gap = DistributionPreviewCoverage(ranges = listOf("[1.0,2.0)", "[3.0,4.0)"))
        val inGap = preview("2.5", coverage = gap)
        assertFalse(inGap.supported)
        assertEquals(emptyList<DistributionPathPreview>(), inGap.paths)
    }

    // ── AC6 (unit side): version validation ──────────────────────────────────

    @Test
    @DisplayName("SYS-107: a blank or non-numeric version is rejected with a message naming version")
    fun `SYS-107 non-numeric version is rejected`() {
        listOf("abc", "", "   ").forEach { bad ->
            val e = assertThrows<IllegalArgumentException> { preview(bad) }
            assertTrue(e.message!!.startsWith("version"), e.message)
        }
        assertEquals("1.5", preview(" 1.5 ").version)
    }

    // ── shared helpers (also used by toResolvedEscrowModuleConfig / renderResolved) ──

    @Test
    @DisplayName("SYS-107: override reading - unparseable never matches; containment otherwise")
    fun `SYS-107 overrideRangeContains`() {
        assertTrue(rangeFactory.overrideRangeContains("[1.0,2.0)", v("1.5")))
        assertFalse(rangeFactory.overrideRangeContains("[1.0,2.0)", v("2.0")))
        assertFalse(rangeFactory.overrideRangeContains("not-a-range", v("1.5")))
        assertEquals(null, rangeFactory.rangeContainsOrNull("not-a-range", v("1.5")))
        assertEquals(false, rangeFactory.rangeContainsOrNull("[1.0,2.0)", v("3")))
        assertEquals(true, rangeFactory.rangeContainsOrNull("[1.0,2.0)", v("1.0")))
    }

    @Test
    @DisplayName("SYS-107: coverage reading - blank, all-versions and unparseable count as containing")
    fun `SYS-107 coverageRangeContains`() {
        assertTrue(rangeFactory.coverageRangeContains(null, v("9")))
        assertTrue(rangeFactory.coverageRangeContains("", v("9")))
        assertTrue(rangeFactory.coverageRangeContains(ALL_VERSIONS, v("9")))
        assertTrue(rangeFactory.coverageRangeContains("not-a-range", v("9")))
        assertTrue(rangeFactory.coverageRangeContains("[1.0,2.0)", v("1.5")))
        assertFalse(rangeFactory.coverageRangeContains("[1.0,2.0)", v("9")))
    }

    @Test
    @DisplayName("SYS-107: coverage gate - presence ranges united with a bounded base; no restriction supports all")
    fun `SYS-107 isInCoverage`() {
        assertTrue(rangeFactory.isInCoverage(emptyList(), ALL_VERSIONS, v("9")))
        assertTrue(rangeFactory.isInCoverage(listOf("[1,2)"), ALL_VERSIONS, v("1.5")))
        assertFalse(rangeFactory.isInCoverage(listOf("[1,2)"), ALL_VERSIONS, v("9")))
        assertTrue(rangeFactory.isInCoverage(emptyList(), "[1,2)", v("1.5")))
        assertFalse(rangeFactory.isInCoverage(emptyList(), "[1,2)", v("9")))
        assertTrue(rangeFactory.isInCoverage(listOf("[5,6)"), "[1,2)", v("1.5")))
        assertTrue(rangeFactory.isInCoverage(listOf("[5,6)"), "[1,2)", v("5.5")))
        assertFalse(rangeFactory.isInCoverage(listOf("[5,6)"), "[1,2)", v("3")))
    }

    @Test
    @DisplayName("SYS-107: candidate ranges are normalised like the save path (trim + strip whitespace)")
    fun `SYS-107 normalizeVersionRange`() {
        assertEquals("[1.0,2.0)", normalizeVersionRange(" [1.0, 2.0)\t"))
    }
}
