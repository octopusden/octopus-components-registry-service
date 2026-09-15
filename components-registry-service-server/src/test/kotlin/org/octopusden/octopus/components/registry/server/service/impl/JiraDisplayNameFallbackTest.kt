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
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `JiraComponent.displayName` is what the downstream Jira release plugin renders as the
 * release-notification subject and the release-note header; when it is blank that plugin
 * falls back to the bare Jira project name, which is shared by every component in the
 * project. The value used to be sourced ONLY from the opt-in `jira { displayName = ... }`
 * DSL attribute, which most components never set — so they all rendered the same
 * project name instead of their own `componentDisplayName`.
 *
 * Effective display name, Jira surfaces: `jiraDisplayName ?: displayName`, nullable
 * (null still lets the plugin fall back to the project name). Pinned here at the single
 * join site, `EntityMappers.buildJiraComponent`, which every v2/v4 Jira surface routes
 * through. See docs/registry/adr/021.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class JiraDisplayNameFallbackTest {
    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            NumericVersionFactory(versionNames),
            VersionRangeFactory(versionNames),
            versionNames,
        )
    }

    private fun makeComponent(
        key: String,
        displayName: String?,
        jiraDisplayName: String?,
    ) {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = key)
        comp.displayName = displayName
        comp.jiraDisplayName = jiraDisplayName
        comp.configurations.add(
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                deprecated = false,
                jiraProjectKey = "PRJ",
            ),
        )
        `when`(componentRepository.findByComponentKey(key)).thenReturn(comp)
    }

    private fun jiraDisplayNameOf(key: String): String? {
        val cfg = resolver.getResolvedComponentDefinition(key, "1.0.0")
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
        // Unlike display_name, jira_display_name is stored verbatim by the v4 create/PATCH paths,
        // so "" reaches the mapper. A plain `?:` would short-circuit and hand the downstream
        // plugin a blank name — the very fallback this resolution exists to avoid.
        makeComponent("comp-blank", displayName = "Component Blank", jiraDisplayName = "   ")
        assertEquals("Component Blank", jiraDisplayNameOf("comp-blank"))
    }

    @Test
    @DisplayName("neither name set → stays null (the plugin keeps falling back to the project name)")
    fun `both unset stays null`() {
        makeComponent("comp-three", displayName = null, jiraDisplayName = null)
        assertNull(jiraDisplayNameOf("comp-three"))
    }
}
