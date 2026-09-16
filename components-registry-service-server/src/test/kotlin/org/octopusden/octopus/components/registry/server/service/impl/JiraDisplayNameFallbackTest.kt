package org.octopusden.octopus.components.registry.server.service.impl

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
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.JiraComponentVersionToDetailedComponentVersionMapper
import org.octopusden.octopus.components.registry.server.mapper.toDetailResponse
import org.octopusden.octopus.components.registry.server.mapper.toSummaryResponse
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The effective display name on a Jira surface, and the two surfaces the rule must NOT
 * reach. See docs/registry/adr/021.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class JiraDisplayNameFallbackTest {
    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            numericVersionFactory,
            VersionRangeFactory(versionNames),
            versionNames,
        )
    }

    private fun makeComponent(
        key: String,
        displayName: String?,
        jiraDisplayName: String?,
    ): ComponentEntity {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = key)
        comp.displayName = displayName
        comp.jiraDisplayName = jiraDisplayName
        comp.configurations.add(
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = comp,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                deprecated = false,
                jiraProjectKey = "PRJ",
                jiraMinorVersionFormat = "\$major",
                jiraReleaseVersionFormat = "\$major.\$minor",
            ),
        )
        `when`(componentRepository.findByComponentKey(key)).thenReturn(comp)
        return comp
    }

    private fun jiraDisplayNameOf(key: String): String? {
        val cfg = resolver.getResolvedComponentDefinition(key, "1.0")
        assertNotNull(cfg)
        return cfg!!.jiraConfiguration.displayName
    }

    @Test
    @DisplayName("no jira.displayName → falls back to componentDisplayName (not the Jira project name)")
    fun `falls back to component display name`() {
        makeComponent("comp-one", displayName = "Component One", jiraDisplayName = null)
        assertEquals("Component One", jiraDisplayNameOf("comp-one"))
    }

    @Test
    @DisplayName("an explicit jira.displayName still wins over componentDisplayName")
    fun `explicit jira display name wins`() {
        makeComponent("comp-two", displayName = "Component Two", jiraDisplayName = "Component Two In Jira")
        assertEquals("Component Two In Jira", jiraDisplayNameOf("comp-two"))
    }

    @Test
    @DisplayName("a blank jira.displayName falls back too — the v4 write path does not normalize it")
    fun `blank jira display name falls back`() {
        makeComponent("comp-blank", displayName = "Component Blank", jiraDisplayName = "   ")
        assertEquals("Component Blank", jiraDisplayNameOf("comp-blank"))
    }

    @Test
    @DisplayName("neither name set → stays null (the plugin keeps falling back to the project name)")
    fun `both unset stays null`() {
        makeComponent("comp-three", displayName = null, jiraDisplayName = null)
        assertNull(jiraDisplayNameOf("comp-three"))
    }

    @Test
    @DisplayName("DetailedComponentVersion.component follows the rule (is a display name, not an id)")
    fun `detailed component version carries the resolved display name`() {
        makeComponent("comp-detailed", displayName = "Component Detailed", jiraDisplayName = null)
        val mapper =
            JiraComponentVersionToDetailedComponentVersionMapper(
                JiraComponentVersionFormatter(versionNames),
                numericVersionFactory,
            )

        val detailed = mapper.convert(resolver.getJiraComponentVersion("comp-detailed", "1.0"))

        assertEquals("Component Detailed", detailed.component)
    }

    @Test
    @DisplayName("EXCLUSION: legacy \$.name stays null when only jiraDisplayName is set")
    fun `legacy component display name is not resolved`() {
        makeComponent("comp-jira-only", displayName = null, jiraDisplayName = "Component Jira Only")

        val cfg = resolver.getResolvedComponentDefinition("comp-jira-only", "1.0")

        assertNotNull(cfg)
        assertEquals("Component Jira Only", cfg!!.jiraConfiguration.displayName)
        // Flipping this moves the compat baseline.
        assertNull(cfg.componentDisplayName)
    }

    @Test
    @DisplayName("EXCLUSION: V4 editor fields expose the stored columns, never the resolved name")
    fun `v4 responses expose the raw columns`() {
        // A resolved value here would be written back into display_name on the next save.
        val comp = makeComponent("comp-editor", displayName = null, jiraDisplayName = "Component Jira Only")

        assertNull(comp.toDetailResponse().displayName)
        assertNull(comp.toSummaryResponse().displayName)
        assertEquals("Component Jira Only", comp.jiraDisplayName)
    }
}
