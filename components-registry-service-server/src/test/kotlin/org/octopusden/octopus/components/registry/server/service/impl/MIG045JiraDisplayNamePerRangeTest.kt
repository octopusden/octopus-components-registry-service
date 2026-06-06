package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * MIG-045 — per-range `jira.displayName` for jira-component-version-ranges parity.
 *
 * V1 resolves displayName per EscrowModuleConfig / version range. Schema-v2 stored
 * only `components.jira_display_name` (component-level) and ignored per-range DSL
 * overrides — causing TYPE_MISMATCH NULL↔STRING on CARDS/ANCS compat.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG045JiraDisplayNamePerRangeTest {

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

    private fun makeComponent(key: String, jiraDisplayName: String? = null): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key).apply {
            this.jiraDisplayName = jiraDisplayName
        }

    private fun makeBase(
        component: ComponentEntity,
        versionRange: String = ALL_VERSIONS,
        jiraProjectKey: String = "SYNTH",
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            deprecated = false,
            jiraProjectKey = jiraProjectKey,
        )

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

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    @Test
    @DisplayName("MIG-045-001: per-range jira.displayName override surfaces on jira-component-version-ranges")
    fun `MIG-045-001 per-range jira displayName override on jira-component-version-ranges`() {
        val comp = makeComponent("alpha-fixture", jiraDisplayName = "Component Default")
        val base = makeBase(comp, jiraProjectKey = "SYNTH")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "jira.displayName")
        overrideRow.jiraDisplayName = "Range Override"

        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")

        val defaultRange = ranges.first { it.versionRange == ALL_VERSIONS }
        val overrideRange = ranges.first { it.versionRange == "[1.0,2.0)" }

        assertEquals(
            "Component Default",
            defaultRange.component.displayName,
            "base range must inherit component-level jiraDisplayName",
        )
        assertEquals(
            "Range Override",
            overrideRange.component.displayName,
            "override range must use per-range jira.displayName scalar",
        )
    }

    @Test
    @DisplayName("MIG-045-002: null jira.displayName override clears inherited displayName for that range")
    fun `MIG-045-002 null jira displayName override clears inherited displayName for range`() {
        val comp = makeComponent("beta-fixture", jiraDisplayName = "Component Default")
        val base = makeBase(comp, jiraProjectKey = "SYNTH")
        val nullOverrideRow = makeScalarOverrideRow(comp, "[2.0,)", "jira.displayName")
        // jiraDisplayName intentionally null — null-clear override

        comp.configurations.addAll(listOf(base, nullOverrideRow))
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")

        assertEquals("Component Default", ranges.first { it.versionRange == ALL_VERSIONS }.component.displayName)
        assertNull(
            ranges.first { it.versionRange == "[2.0,)" }.component.displayName,
            "null-clear jira.displayName override must not fall back to component.jiraDisplayName",
        )
    }

    @Test
    @DisplayName("MIG-045-003: synthetic base range with explicit null displayName does not inherit component default")
    fun `MIG-045-003 synthetic base range explicit null displayName does not inherit component default`() {
        val comp = makeComponent("gamma-fixture", jiraDisplayName = "Component Default")
        val base =
            makeBase(comp, versionRange = "[1.0,2.0)", jiraProjectKey = "SYNTH").apply {
                jiraDisplayName = null
            }

        comp.configurations.add(base)
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("SYNTH")

        assertNull(
            ranges.first { it.versionRange == "[1.0,2.0)" }.component.displayName,
            "synthetic base range with null jira.displayName must not fall back to component.jiraDisplayName",
        )
    }
}
