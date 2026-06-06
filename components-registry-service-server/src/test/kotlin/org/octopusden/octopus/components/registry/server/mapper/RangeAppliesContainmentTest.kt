package org.octopusden.octopus.components.registry.server.mapper

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.service.impl.DatabaseComponentRegistryResolver
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * TD-010 / MIG-046: `rangeApplies` containment heuristic for enumeration endpoints.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class RangeAppliesContainmentTest {

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)

    @ParameterizedTest(name = "TD-010-{0}: parent={1} child={2} expected={3}")
    @MethodSource("boundedMatrix")
    @DisplayName("TD-010 bounded rangeApplies matrix")
    fun `TD-010 bounded rangeApplies matrix`(
        @Suppress("UNUSED_PARAMETER") caseId: String,
        parent: String,
        child: String,
        expected: Boolean,
    ) {
        val actual =
            rangeApplies(
                parentRange = parent,
                childRange = child,
                versionRangeFactory = versionRangeFactory,
                numericVersionFactory = numericVersionFactory,
            )
        assertEquals(expected, actual, "rangeApplies($parent, $child)")
    }

    @Test
    @DisplayName("TD-010-012: unbounded ALL_VERSIONS parent contains bounded child")
    fun `TD-010-012 unbounded ALL_VERSIONS parent contains bounded child`() {
        assertEquals(
            true,
            rangeApplies(
                parentRange = ALL_VERSIONS,
                childRange = "[1.0,2.0)",
                versionRangeFactory = versionRangeFactory,
                numericVersionFactory = numericVersionFactory,
            ),
        )
    }

    companion object {
        @JvmStatic
        fun boundedMatrix(): List<Arguments> =
            listOf(
                Arguments.of("001", "[1.0,2.0)", "[1.0,2.0)", true),
                Arguments.of("002", "[1.0,3.0)", "[1.0,2.0)", true),
                Arguments.of("003", "[1.0,3.0)", "[2.0,3.0)", true),
                Arguments.of("004", "[1.0,3.0)", "[1.5,2.5)", true),
                Arguments.of("005", "[1.0,2.0)", "[1.5,2.5)", false),
                Arguments.of("006", "[1.0,2.0]", "[2.0,3.0)", false),
                Arguments.of("007", "[1.0,2.0)", "[2.0,3.0)", false),
                Arguments.of("008", "(1.0,3.0)", "[1.5,2.5]", true),
                Arguments.of("009", "[1.0,2.0)", "[1.0,2.0]", false),
            )
    }
}

/**
 * MIG-046 integration pins for jira-component-version-ranges parity:
 * broad-range scalar overrides + per-range vcs.externalRegistry layering.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG046JiraComponentVersionRangesParityTest {

    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver =
            DatabaseComponentRegistryResolver(
                componentRepository,
                dependencyMappingRepository,
                numericVersionFactory,
                versionRangeFactory,
                versionNames,
            )
    }

    private fun makeComponent(
        key: String,
        jiraDisplayName: String? = null,
        vcsExternalRegistry: String? = null,
    ): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key).apply {
            this.jiraDisplayName = jiraDisplayName
            this.vcsExternalRegistry = vcsExternalRegistry
        }

    private fun makeBase(
        component: ComponentEntity,
        versionRange: String = ALL_VERSIONS,
        jiraProjectKey: String = "SYNTH",
        vcsExternalRegistry: String? = null,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            deprecated = false,
            jiraProjectKey = jiraProjectKey,
        ).apply {
            this.vcsExternalRegistry = vcsExternalRegistry
        }

    private fun makeScalarOverrideRow(
        component: ComponentEntity,
        versionRange: String,
        attribute: String,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = attribute,
            rowType = "SCALAR_OVERRIDE",
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

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    @Test
    @DisplayName("MIG-046-001: broad-range jira.displayName override applies to contained enumeration range")
    fun `MIG-046-001 broad range jira displayName override applies to contained enumeration range`() {
        val comp = makeComponent("broad-display-fixture")
        val base = makeBase(comp)
        val broadOverride = makeScalarOverrideRow(comp, "[1.0,3.0)", "jira.displayName")
        broadOverride.jiraDisplayName = "Broad Override"

        comp.configurations.addAll(
            listOf(
                base,
                broadOverride,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[1.0,2.0)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")
        val narrow = ranges.first { it.versionRange == "[1.0,2.0)" }

        assertEquals("Broad Override", narrow.component.displayName)
    }

    @Test
    @DisplayName("MIG-046-002: per-range vcs.externalRegistry null-clear does not inherit component default")
    fun `MIG-046-002 per-range vcs externalRegistry null-clear does not inherit component default`() {
        val comp = makeComponent("vcs-registry-fixture", vcsExternalRegistry = "ssh://component-default")
        val base =
            makeBase(comp, vcsExternalRegistry = "ssh://component-default").apply {
                vcsEntries.add(
                    VcsSettingsEntryEntity(
                        componentConfiguration = this,
                        name = "main",
                        vcsPath = "ssh://base-root",
                        sortOrder = 0,
                    ),
                )
            }
        val nullClear = makeScalarOverrideRow(comp, "[1.0,2.0)", "vcs.externalRegistry")
        val vcsMarker = makeMarkerRow(comp, "[1.0,2.0)", MarkerAttributes.VCS_SETTINGS)

        comp.configurations.addAll(listOf(base, nullClear, vcsMarker))
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")
        val overrideRange = ranges.first { it.versionRange == "[1.0,2.0)" }

        assertNull(
            overrideRange.vcsSettings.externalRegistry,
            "null-clear vcs.externalRegistry must not fall back to components.vcs_external_registry",
        )
        assertEquals(0, overrideRange.vcsSettings.versionControlSystemRoots.size)
    }

    @Test
    @DisplayName("MIG-046-003: per-range vcs.externalRegistry override surfaces on jira-component-version-ranges")
    fun `MIG-046-003 per-range vcs externalRegistry override surfaces on jira-component-version-ranges`() {
        val comp = makeComponent("vcs-registry-override-fixture", vcsExternalRegistry = "ssh://component-default")
        val base = makeBase(comp, vcsExternalRegistry = "ssh://base-registry")
        val overrideRow = makeScalarOverrideRow(comp, "[2.0,)", "vcs.externalRegistry")
        overrideRow.vcsExternalRegistry = "ssh://range-registry"

        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")

        assertEquals("ssh://base-registry", ranges.first { it.versionRange == ALL_VERSIONS }.vcsSettings.externalRegistry)
        assertEquals(
            "ssh://range-registry",
            ranges.first { it.versionRange == "[2.0,)" }.vcsSettings.externalRegistry,
        )
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
                    org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity(
                        componentConfiguration = this,
                        groupPattern = "com.example",
                        artifactPattern = "artifact",
                        sortOrder = 0,
                    ),
                )
            }
        val distMarker =
            makeMarkerRow(comp, "[1.0,2.0)", MarkerAttributes.DISTRIBUTION_MAVEN)

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
