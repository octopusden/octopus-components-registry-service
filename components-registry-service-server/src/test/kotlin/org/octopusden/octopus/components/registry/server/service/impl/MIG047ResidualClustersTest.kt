package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * MIG-047 — compat residual clusters: version-gap 404s, vcs.settings registry
 * isolation, distribution marker clearing on jira-component-version-ranges.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG047ResidualClustersTest {

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

    private fun makeComponent(
        key: String,
        vcsExternalRegistry: String? = null,
    ): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key).apply {
            this.vcsExternalRegistry = vcsExternalRegistry
        }

    private fun makeBase(
        component: ComponentEntity,
        jiraProjectKey: String = "SYNTH",
        vcsExternalRegistry: String? = null,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            deprecated = false,
            jiraProjectKey = jiraProjectKey,
        ).apply {
            this.vcsExternalRegistry = vcsExternalRegistry
        }

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

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    @Test
    @DisplayName("MIG-047-001: version in gap between explicit DSL ranges resolves to null")
    fun `MIG-047-001 version in gap between explicit DSL ranges resolves to null`() {
        val comp = makeComponent("authmodlib-gap-fixture")
        val base =
            makeBase(comp).apply {
                versionRange = "[10,11)"
                isSyntheticBase = true
            }
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[10,11)",
                    rowType = "RANGE_PRESENCE",
                ),
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[11,12.1)",
                    rowType = "RANGE_PRESENCE",
                ),
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[12.2,)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        assertNull(
            resolver.getResolvedComponentDefinition("authmodlib-gap-fixture", "12.1.155"),
            "12.1.155 sits in the gap between [11,12.1) and [12.2,) — must not resolve",
        )
        assertNotNull(resolver.getResolvedComponentDefinition("authmodlib-gap-fixture", "11.5.0"))
    }

    @Test
    @DisplayName("MIG-047-002: vcs.settings marker does not inherit components.vcs_external_registry")
    fun `MIG-047-002 vcs settings marker does not inherit components vcs external registry`() {
        val comp = makeComponent("vcs-marker-registry-fixture", vcsExternalRegistry = "ssh://component-default")
        val base =
            makeBase(comp).apply {
                vcsExternalRegistry = "ssh://base-registry"
                vcsEntries.add(
                    VcsSettingsEntryEntity(
                        componentConfiguration = this,
                        name = "main",
                        vcsPath = "ssh://base-root",
                        sortOrder = 0,
                    ),
                )
            }
        val vcsMarker = makeMarkerRow(comp, "[1.0,2.0)", MarkerAttributes.VCS_SETTINGS)

        comp.configurations.addAll(listOf(base, vcsMarker))
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")
        val overrideRange = ranges.first { it.versionRange == "[1.0,2.0)" }

        assertNull(
            overrideRange.vcsSettings.externalRegistry,
            "vcs.settings marker must not fall back to components.vcs_external_registry",
        )
        assertEquals(0, overrideRange.vcsSettings.versionControlSystemRoots.size)
    }

    @Test
    @DisplayName("MIG-047-003: empty distribution marker clears distribution on jira-component-version-ranges")
    fun `MIG-047-003 empty distribution marker clears distribution on jira ranges`() {
        val comp = makeComponent("distribution-clear-fixture")
        comp.distributionExplicit = true
        comp.distributionExternal = false
        val base =
            makeBase(comp).apply {
                mavenArtifacts.add(
                    DistributionMavenArtifactEntity(
                        componentConfiguration = this,
                        groupPattern = "com.example",
                        artifactPattern = "artifact",
                        sortOrder = 0,
                    ),
                )
            }
        val distMarker = makeMarkerRow(comp, "[1.0,2.0)", MarkerAttributes.DISTRIBUTION_MAVEN)

        comp.configurations.addAll(listOf(base, distMarker))
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")

        assertNotNull(ranges.first { it.versionRange == ALL_VERSIONS }.distribution?.GAV())
        assertNull(
            ranges.first { it.versionRange == "[1.0,2.0)" }.distribution,
            "distribution.maven marker with no artifacts must clear distribution for the range",
        )
    }
}
