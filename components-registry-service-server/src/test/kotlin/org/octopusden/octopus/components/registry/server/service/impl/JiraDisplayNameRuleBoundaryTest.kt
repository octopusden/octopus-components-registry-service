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
 * Boundaries of the effective-display-name rule (docs/registry/adr/021).
 *
 * The rule resolves a name for RENDERING. Two kinds of surface are deliberately
 * excluded, and this pins both, because neither exclusion is visible from the code
 * that implements the rule:
 *
 *  - the legacy v1/v2/v3 `$.name` (fed from `display_name` verbatim) must keep serving
 *    null, or the compat baseline moves for every component that has only a
 *    `jiraDisplayName`;
 *  - the V4 editor fields must expose the stored columns, or opening a component and
 *    saving an unrelated field persists the resolved value into the column — which
 *    breaches the first exclusion, emits as-code DSL nobody wrote, and can collide with
 *    the UNIQUE constraint on `display_name`.
 *
 * It also pins the one surface the rule DOES reach beyond the Jira DTO:
 * `DetailedComponentVersion.component`.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class JiraDisplayNameRuleBoundaryTest {
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
        // The Jira surface resolves...
        assertEquals("Component Jira Only", cfg!!.jiraConfiguration.displayName)
        // ...the legacy wire does not. Flipping this moves the compat baseline.
        assertNull(cfg.componentDisplayName)
    }

    @Test
    @DisplayName("EXCLUSION: V4 editor fields expose the stored columns, never the resolved name")
    fun `v4 responses expose the raw columns`() {
        val comp = makeComponent("comp-editor", displayName = null, jiraDisplayName = "Component Jira Only")

        // A resolved value here would be written back into display_name on the next save.
        assertNull(comp.toDetailResponse().displayName)
        assertNull(comp.toSummaryResponse().displayName)
        assertEquals("Component Jira Only", comp.jiraDisplayName)
    }
}
