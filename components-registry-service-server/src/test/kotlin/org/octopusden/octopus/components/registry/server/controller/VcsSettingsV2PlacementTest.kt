package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.Mapper
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.service.impl.DatabaseComponentRegistryResolver
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ONB-001: the v2 `GET rest/api/2/components/{c}/versions/{v}/vcs-settings` body carries a placed entry's
 * `sourcePath` and `checkoutDirectory` after the six existing fields; unplaced bodies are pinned by
 * [VcsSettingsV2BaselineTest]. Same harness: real [ComponentControllerV2] and
 * [DatabaseComponentRegistryResolver] over an in-memory entity graph.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class VcsSettingsV2PlacementTest {
    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val resolver =
            DatabaseComponentRegistryResolver(
                componentRepository,
                mock(DependencyMappingRepository::class.java),
                NumericVersionFactory(versionNames),
                VersionRangeFactory(versionNames),
                versionNames,
            )

        @Suppress("UNCHECKED_CAST")
        val controller =
            ComponentControllerV2(
                mock(Mapper::class.java) as Mapper<JiraComponentVersion, DetailedComponentVersion>,
            )
        ReflectionTestUtils.setField(controller, "componentRegistryResolver", resolver)
        mvc = MockMvcBuilders.standaloneSetup(controller).build()
    }

    private fun component(vararg entries: (ComponentConfigurationEntity) -> VcsSettingsEntryEntity) {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = COMPONENT)
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                buildSystem = "GRADLE",
                jiraProjectKey = "TESTPROJ",
                deprecated = false,
            )
        base.jiraMinorVersionFormat = "\$major.\$minor"
        base.jiraReleaseVersionFormat = "\$major.\$minor.\$service"
        entries.forEach { base.vcsEntries.add(it(base)) }
        comp.configurations.add(base)
        `when`(componentRepository.findByComponentKey(COMPONENT)).thenReturn(comp)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))
    }

    private fun vcsSettingsBody(version: String = "1.0.1"): String =
        mvc
            .perform(get("/rest/api/2/components/$COMPONENT/versions/$version/vcs-settings"))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString

    @Test
    @DisplayName("ONB-001: v2 vcs-settings carry sourcePath and checkoutDirectory of placed entries, omitted when empty")
    fun `v2 vcs-settings placed entries`() {
        component(
            { config ->
                VcsSettingsEntryEntity(
                    componentConfiguration = config,
                    name = "main",
                    vcsPath = "ssh://git@example.test/proj/repo-a.git",
                    branch = "master",
                    repositoryType = "GIT",
                    sortOrder = 0,
                    sourcePath = "mapper",
                )
            },
            { config ->
                VcsSettingsEntryEntity(
                    componentConfiguration = config,
                    name = "feature",
                    vcsPath = "ssh://git@example.test/proj/repo-b.git",
                    branch = "master",
                    repositoryType = "GIT",
                    sortOrder = 1,
                    sourcePath = "data",
                    checkoutDirectory = "feature",
                )
            },
        )

        assertEquals(
            """{"versionControlSystemRoots":[""" +
                """{"name":"main","vcsPath":"ssh://git@example.test/proj/repo-a.git","type":"GIT","branch":"master",""" +
                """"sourcePath":"mapper"},""" +
                """{"name":"feature","vcsPath":"ssh://git@example.test/proj/repo-b.git","type":"GIT","branch":"master",""" +
                """"sourcePath":"data","checkoutDirectory":"feature"}""" +
                """],"externalRegistry":null}""",
            vcsSettingsBody(),
        )
    }

    @Test
    @DisplayName("ONB-001 rev. 3: a per-range row's buildWorkingDirectory reaches v2 inside its range only")
    fun `v2 vcs-settings per-range build working directory`() {
        component({ config -> VcsSettingsEntryEntity(componentConfiguration = config, name = "main", vcsPath = REPO, repositoryType = "GIT") })
        val comp = componentRepository.findByComponentKey(COMPONENT)!!
        val marker =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[2,3)",
                overriddenAttribute = "vcs.settings",
                rowType = "MARKER",
            )
        marker.buildWorkingDirectory = "core"
        marker.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = marker, name = "core", vcsPath = REPO, repositoryType = "GIT", checkoutDirectory = "core"),
        )
        comp.configurations.add(marker)

        assertEquals(
            """{"versionControlSystemRoots":[{"name":"core","vcsPath":"$REPO","type":"GIT","branch":"master","checkoutDirectory":"core"}],""" +
                """"externalRegistry":null,"buildWorkingDirectory":"core"}""",
            vcsSettingsBody("2.1"),
        )
        assertEquals(
            """{"versionControlSystemRoots":[{"name":"main","vcsPath":"$REPO","type":"GIT","branch":"master"}],"externalRegistry":null}""",
            vcsSettingsBody("1.0.1"),
        )
    }

    companion object {
        private const val COMPONENT = "test-component-a"
        private const val ALL_VERSIONS = "(,0),[0,)"
        private const val REPO = "ssh://git@example.test/proj/repo-a.git"
    }
}
