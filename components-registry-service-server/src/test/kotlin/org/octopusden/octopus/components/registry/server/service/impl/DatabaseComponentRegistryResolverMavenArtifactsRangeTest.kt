package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * TDD regression tests for RES-C: getMavenArtifactParameters must return per-range
 * groupPattern/artifactPattern by walking the EscrowModule view rather than
 * returning the component-level artifactIds for every range.
 *
 * Bug: when a component has DISTRIBUTION_MAVEN marker rows overriding the GAV per
 * range, the old implementation ignored the overrides and returned the same
 * component-level artifact IDs for every range. The fixed implementation consults
 * config.distribution?.GAV() per EscrowModuleConfig, falling back to component-level
 * artifactIds only when no per-range GAV is present.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class DatabaseComponentRegistryResolverMavenArtifactsRangeTest {

    // ========================================================================
    // Infrastructure
    // ========================================================================

    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )
    }

    // ========================================================================
    // Entity helpers
    // ========================================================================

    private fun makeComponent(key: String): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun makeBase(
        component: ComponentEntity,
        versionRange: String = ALL_VERSIONS,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = null,
            rowType = "BASE",
            deprecated = false,
        )

    private fun makeMarkerRow(
        component: ComponentEntity,
        versionRange: String,
        attribute: String,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = attribute,
            rowType = "MARKER",
        )

    private fun addMavenArtifact(
        config: ComponentConfigurationEntity,
        groupPattern: String,
        artifactPattern: String,
        sortOrder: Int = 0,
    ) {
        config.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                componentConfiguration = config,
                groupPattern = groupPattern,
                artifactPattern = artifactPattern,
                sortOrder = sortOrder,
            ),
        )
    }

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    @DisplayName(
        "RES-C-001: per-range DISTRIBUTION_MAVEN marker overrides groupPattern " +
            "(bug-C-component-A shape: two ranges with distinct groupId per range)",
    )
    fun `RES-C-001 getMavenArtifactParameters returns per-range groupPattern from marker override`() {
        // bug-C-component-A shape:
        //   BASE at [1.1,) with maven artifact (com.example.ic, bug-C-component-A)
        //   MARKER distribution.maven at [1.0,1.1) with maven artifact (com.example, bug-C-component-A)
        val comp = makeComponent("bug-C-component-A-like")
        comp.distributionExplicit = true

        val base = makeBase(comp, "[1.1,)")
        addMavenArtifact(base, "com.example.ic", "bug-C-component-A")

        val markerOld = makeMarkerRow(comp, "[1.0,1.1)", "distribution.maven")
        addMavenArtifact(markerOld, "com.example", "bug-C-component-A")

        comp.configurations.addAll(listOf(base, markerOld))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("bug-C-component-A-like")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeOld = result["[1.0,1.1)"]
        assertNotNull(rangeOld, "[1.0,1.1) entry must be present")
        assertEquals("com.example", rangeOld!!.groupPattern, "[1.0,1.1) groupPattern must be com.example")
        assertEquals("bug-C-component-A", rangeOld.artifactPattern, "[1.0,1.1) artifactPattern must be bug-C-component-A")

        val rangeNew = result["[1.1,)"]
        assertNotNull(rangeNew, "[1.1,) entry must be present")
        assertEquals(
            "com.example.ic",
            rangeNew!!.groupPattern,
            "[1.1,) groupPattern must be com.example.ic",
        )
        assertEquals("bug-C-component-A", rangeNew.artifactPattern, "[1.1,) artifactPattern must be bug-C-component-A")
    }

    @Test
    @DisplayName(
        "RES-C-002: per-range DISTRIBUTION_MAVEN marker override with multiple artifacts " +
            "(bug-C-component-B shape: multi-artifact CSV, two ranges)",
    )
    fun `RES-C-002 getMavenArtifactParameters handles multi-artifact CSV override`() {
        // bug-C-component-B shape:
        //   BASE at [03.51.29.15,) with maven artifact (com.example.cardsmodel2.dummy, bug-C-component-B)
        //   MARKER distribution.maven at (,03.51.29.15) with two artifacts:
        //     (com.example.cardsmodel2, bug-C-fixture-v2) and (com.example.cardsmodel, bug-C-fixture-legacy)
        val comp = makeComponent("bug-C-fixture-B-shape")
        comp.distributionExplicit = true

        val base = makeBase(comp, "[03.51.29.15,)")
        addMavenArtifact(base, "com.example.cardsmodel2.dummy", "bug-C-component-B", sortOrder = 0)

        val markerOld = makeMarkerRow(comp, "(,03.51.29.15)", "distribution.maven")
        addMavenArtifact(markerOld, "com.example.cardsmodel2", "bug-C-fixture-v2", sortOrder = 0)
        addMavenArtifact(markerOld, "com.example.cardsmodel", "bug-C-fixture-legacy", sortOrder = 1)

        comp.configurations.addAll(listOf(base, markerOld))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("bug-C-fixture-B-shape")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeOld = result["(,03.51.29.15)"]
        assertNotNull(rangeOld, "(,03.51.29.15) entry must be present")
        // groupPattern = first artifact's groupId
        assertEquals(
            "com.example.cardsmodel2",
            rangeOld!!.groupPattern,
            "(,03.51.29.15) groupPattern must be first artifact's groupId",
        )
        // artifactPattern = CSV join of all artifact IDs in sort order
        assertEquals(
            "bug-C-fixture-v2,bug-C-fixture-legacy",
            rangeOld.artifactPattern,
            "(,03.51.29.15) artifactPattern must be CSV of both artifact IDs",
        )

        val rangeNew = result["[03.51.29.15,)"]
        assertNotNull(rangeNew, "[03.51.29.15,) entry must be present")
        assertEquals(
            "com.example.cardsmodel2.dummy",
            rangeNew!!.groupPattern,
            "[03.51.29.15,) groupPattern must be com.example.cardsmodel2.dummy",
        )
        assertEquals("bug-C-component-B", rangeNew.artifactPattern, "[03.51.29.15,) artifactPattern must be bug-C-component-B")
    }

    @Test
    @DisplayName(
        "RES-C-003: component with single ALL_VERSIONS range and no marker overrides " +
            "falls back to component-level artifactIds",
    )
    fun `RES-C-003 getMavenArtifactParameters falls back to component-level artifactIds when no GAV override`() {
        // No per-range overrides: only a BASE row with maven artifact at ALL_VERSIONS
        val comp = makeComponent("simple-component")
        comp.distributionExplicit = true

        val base = makeBase(comp, ALL_VERSIONS)
        addMavenArtifact(base, "com.example", "simple-lib")

        comp.configurations.add(base)
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("simple-component")

        assertEquals(1, result.size, "Expected one range entry for ALL_VERSIONS")
        val entry = result[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals("com.example", entry!!.groupPattern, "groupPattern must be com.example")
        assertEquals("simple-lib", entry.artifactPattern, "artifactPattern must be simple-lib")
    }

    // ========================================================================
    // Companion
    // ========================================================================

    companion object {
        /** Must match EscrowConfigurationLoader.ALL_VERSIONS */
        private const val ALL_VERSIONS = "(,0),[0,)"
    }
}
