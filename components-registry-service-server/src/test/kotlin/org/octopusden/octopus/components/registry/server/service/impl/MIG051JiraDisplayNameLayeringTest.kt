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
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * MIG-051 — unified `jira.displayName` layering on read path.
 *
 * Regression guard for the cluster-50 compat explosion (v3 ~50 diffs → 211 on
 * [1.7] after MIG-048 global pre-clear + blanket BASE-anchor suppress):
 * - **RED** on `c9605394`: global `merged.jiraDisplayName = null` for every
 *   enumerated range and suppress on `versionRange == base.versionRange` zeroed
 *   displayName on `/jira-component`, per-version, and common jira-ranges paths
 *   (~131 STRING→NULL) while fixing CARDS enumeration.
 * - **GREEN** with per-view layering in [buildJiraComponent]: RANGE_PRESENCE
 *   empty blocks stay null; explicit ranges inherit `components.jira_display_name`;
 *   single-range explicit null pins stay null.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG051JiraDisplayNameLayeringTest {

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

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    private fun traceReplayStyleComponent(
        key: String = "tskernel-fixture",
        displayName: String = "Transaction Switch Kernel",
        projectKey: String = "TSK",
        explicitRange: String = "[1.0,3.0)",
    ): ComponentEntity {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = key).apply {
                jiraDisplayName = displayName
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = projectKey,
            ).apply {
                isSyntheticBase = true
                vcsEntries.add(
                    VcsSettingsEntryEntity(
                        componentConfiguration = this,
                        name = "main",
                        vcsPath = "ssh://trace-replay-root",
                        sortOrder = 0,
                    ),
                )
            }
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = explicitRange,
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)
        return comp
    }

    @Test
    @DisplayName("MIG-051-001: jira-ranges explicit range inherits component displayName (w4cardsopt pattern)")
    fun `MIG-051-001 jira ranges explicit range inherits component displayName`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "w4cardsopt-fixture").apply {
                jiraDisplayName = "Way4 Cards Opt"
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "CARDS",
            ).apply {
                isSyntheticBase = true
            }
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[3.43.30,3.47.10)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val range = resolver.getJiraComponentVersionRangesByProject("CARDS")
            .first { it.versionRange == "[3.43.30,3.47.10)" }

        assertEquals("Way4 Cards Opt", range.component.displayName)
    }

    @Test
    @DisplayName("MIG-051-002: getJiraComponentVersion inherits component displayName on explicit range")
    fun `MIG-051-002 getJiraComponentVersion inherits component displayName on explicit range`() {
        traceReplayStyleComponent()

        val jiraComponentVersion = resolver.getJiraComponentVersion("tskernel-fixture", "2.5.0")

        assertEquals(
            "Transaction Switch Kernel",
            jiraComponentVersion.component.displayName,
            "Per-version jira-component must not lose components.jira_display_name fallback",
        )
    }

    @Test
    @DisplayName("MIG-051-003: getResolvedComponentDefinition inherits component displayName on explicit range")
    fun `MIG-051-003 getResolvedComponentDefinition inherits component displayName on explicit range`() {
        traceReplayStyleComponent()

        val config = resolver.getResolvedComponentDefinition("tskernel-fixture", "2.5.0")

        assertNotNull(config)
        assertEquals(
            "Transaction Switch Kernel",
            config!!.jiraConfiguration.displayName,
            "Per-version module view must surface component jira.displayName",
        )
    }

    @Test
    @DisplayName("MIG-051-004: getAllJiraComponentVersionRanges inherits component displayName on explicit range")
    fun `MIG-051-004 getAllJiraComponentVersionRanges inherits component displayName on explicit range`() {
        traceReplayStyleComponent()

        val range = resolver.getAllJiraComponentVersionRanges()
            .first { it.componentName == "tskernel-fixture" && it.versionRange == "[1.0,3.0)" }

        assertEquals("Transaction Switch Kernel", range.component.displayName)
    }

    @Test
    @DisplayName("MIG-051-005: RANGE_PRESENCE empty block stays null despite BASE displayName bleed")
    fun `MIG-051-005 RANGE_PRESENCE empty block stays null despite BASE displayName bleed`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "internal-operations-fixture").apply {
                jiraDisplayName = "Internal Operations"
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "CARDS",
                jiraDisplayName = "Internal Operations",
            ).apply {
                isSyntheticBase = true
                vcsEntries.add(
                    VcsSettingsEntryEntity(
                        componentConfiguration = this,
                        name = "main",
                        vcsPath = "ssh://internal-ops-root",
                        sortOrder = 0,
                    ),
                )
            }
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

        val enumerated = resolver.getJiraComponentVersionRangesByProject("CARDS")
            .first { it.versionRange == "[2.0,2.1)" }
        val resolved = resolver.getResolvedComponentDefinition("internal-operations-fixture", "2.0.5")
        val pointInRange = resolver.getJiraComponentVersion("internal-operations-fixture", "2.0.5")

        assertNull(
            enumerated.component.displayName,
            "Empty DSL block on jira-ranges enumeration must stay null",
        )
        assertNull(
            resolved?.jiraConfiguration?.displayName,
            "Empty DSL block on per-version resolution must stay null",
        )
        assertNull(
            pointInRange.component.displayName,
            "Empty DSL block on getJiraComponentVersion must stay null",
        )
    }

    @Test
    @DisplayName("MIG-051-006: single-range BASE explicit null pin does not inherit component default")
    fun `MIG-051-006 single-range BASE explicit null pin does not inherit component default`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "gamma-fixture").apply {
                jiraDisplayName = "Component Default"
            }
        comp.configurations.add(
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[1.0,2.0)",
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "SYNTH",
                jiraDisplayName = null,
            ),
        )
        stubComponent(comp)

        val range = resolver.getJiraComponentVersionRangesByProject("SYNTH")
            .first { it.versionRange == "[1.0,2.0)" }

        assertNull(range.component.displayName)
    }

    @Test
    @DisplayName("MIG-051-007: non-synthetic BASE anchor null row inherits component on per-version path")
    fun `MIG-051-007 non-synthetic BASE anchor null row inherits component on per-version path`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "w4cardsopt-base-anchor-fixture").apply {
                jiraDisplayName = "Way4 Cards Opt"
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[1.0,3.0)",
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "TSK",
                jiraDisplayName = null,
            ).apply {
                vcsEntries.add(
                    VcsSettingsEntryEntity(
                        componentConfiguration = this,
                        name = "main",
                        vcsPath = "ssh://w4cardsopt-root",
                        sortOrder = 0,
                    ),
                )
            }
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[3.5,4.0)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val jiraComponentVersion = resolver.getJiraComponentVersion("w4cardsopt-base-anchor-fixture", "2.5.0")
        val config = resolver.getResolvedComponentDefinition("w4cardsopt-base-anchor-fixture", "2.5.0")

        assertEquals("Way4 Cards Opt", jiraComponentVersion.component.displayName)
        assertEquals("Way4 Cards Opt", config?.jiraConfiguration?.displayName)
    }
}
