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
 * MIG-048 — RANGE_PRESENCE-only explicit ranges must not inherit BASE-row
 * `jira.displayName`. V1 empty DSL blocks yield null displayName on
 * jira-component-version-ranges; bleeding the `(,)` anchor caused NULL↔STRING
 * diffs on CARDS/ANCS compat.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG048JiraDisplayNameRangePresenceTest {

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
        jiraProjectKey: String = "CARDS",
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            buildSystem = "MAVEN",
            deprecated = false,
            jiraProjectKey = jiraProjectKey,
            jiraDisplayName = "Internal Operations",
        )

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    @Test
    @DisplayName("MIG-048-001: RANGE_PRESENCE explicit range does not inherit BASE jira.displayName")
    fun `MIG-048-001 RANGE_PRESENCE explicit range does not inherit BASE jira displayName`() {
        val comp = makeComponent("internal-operations-fixture", jiraDisplayName = "Internal Operations")
        val base = makeBase(comp)
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[2.0,2.1)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("CARDS")

        assertEquals(
            "Internal Operations",
            ranges.first { it.versionRange == ALL_VERSIONS }.component.displayName,
        )
        assertNull(
            ranges.first { it.versionRange == "[2.0,2.1)" }.component.displayName,
            "empty DSL block must not inherit BASE-row jira.displayName",
        )
    }

    @Test
    @DisplayName("MIG-048-002: broad jira.displayName override still applies to contained RANGE_PRESENCE range")
    fun `MIG-048-002 broad jira displayName override still applies to contained RANGE_PRESENCE range`() {
        val comp = makeComponent("broad-presence-fixture", jiraDisplayName = "Component Default")
        val base = makeBase(comp)
        val broadOverride =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[1.0,3.0)",
                overriddenAttribute = "jira.displayName",
                rowType = "SCALAR_OVERRIDE",
                jiraDisplayName = "Broad Override",
            )

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

        val ranges = resolver.getJiraComponentVersionRangesByProject("CARDS")

        assertEquals(
            "Broad Override",
            ranges.first { it.versionRange == "[1.0,2.0)" }.component.displayName,
        )
    }
}
