package org.octopusden.octopus.components.registry.server.service.impl

import org.assertj.core.api.Assertions.assertThat
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
 * `getAllJiraComponentVersionRanges` collects into a `Set`, so the equality contract of its element
 * decides which components the two `jira-component-version-ranges` endpoints show. `componentName`
 * and the distribution both have to be live terms of that contract: without them, components sharing
 * a Jira project, a version range and a VCS root collapse into one, and the loss is silent — no
 * error, no log line. See TD-022 and TD-023.
 *
 * This is the endpoint seam. The model-level contract tests live in
 * `component-resolver-api`; this one pins the behaviour a consumer of the endpoint actually sees.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class RangeSetDropsCollidingComponentsTest {
    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver =
            DatabaseComponentRegistryResolver(
                componentRepository,
                dependencyMappingRepository,
                NumericVersionFactory(versionNames),
                VersionRangeFactory(versionNames),
                versionNames,
            )
    }

    /** Two components whose every other field matches — the shape TD-022 collapsed. */
    private fun component(key: String): ComponentEntity {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = key)
        comp.configurations.add(
            ComponentConfigurationEntity(
                id = UUID.randomUUID(),
                component = comp,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                deprecated = false,
                jiraProjectKey = "SHARED",
                jiraMinorVersionFormat = "\$major",
                jiraReleaseVersionFormat = "\$major.\$minor",
            ),
        )
        `when`(componentRepository.findByComponentKey(key)).thenReturn(comp)
        return comp
    }

    @Test
    @DisplayName("two components identical but for their key both survive the Set")
    fun collidingComponentsBothSurvive() {
        val components = listOf(component("comp-one"), component("comp-two"))
        `when`(componentRepository.findAll()).thenReturn(components)

        val ranges = resolver.getAllJiraComponentVersionRanges()

        assertThat(ranges.map { it.componentName })
            .containsExactlyInAnyOrder("comp-one", "comp-two")
    }

    @Test
    @DisplayName("the same component is still collected once — deduplication is not defeated")
    fun oneComponentStaysOneElement() {
        val comp = component("comp-one")
        `when`(componentRepository.findAll()).thenReturn(listOf(comp))

        assertThat(resolver.getAllJiraComponentVersionRanges()).hasSize(1)
    }
}
