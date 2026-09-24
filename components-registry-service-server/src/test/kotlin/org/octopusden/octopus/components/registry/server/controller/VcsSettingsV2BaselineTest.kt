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
 * ONB-001 baseline (characterization): the exact v2
 * `GET rest/api/2/components/{c}/versions/{v}/vcs-settings` body for a database-backed component.
 *
 * Runs the real [ComponentControllerV2] and [DatabaseComponentRegistryResolver] over an in-memory
 * entity graph (mocked repository, standalone MockMvc, no Spring context or DB). Bodies are compared
 * as exact strings, so a field added to `VersionControlSystemRootDTO` is caught when it is not
 * omitted while empty.
 *
 * Pinned: field set and order (`name, vcsPath, type, tag, branch, hotfixBranch`), `NON_NULL` omission
 * of `tag` / `hotfixBranch`, a blank branch served as the repository type's default, Git `vcsPath`
 * lower-cased on read while CVS keeps its case (`VersionControlSystemRoot.create`), and roots ordered
 * by `sort_order`, not by insertion order.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class VcsSettingsV2BaselineTest {
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

    @Suppress("LongParameterList")
    private fun entry(
        name: String,
        vcsPath: String,
        repositoryType: String?,
        tag: String?,
        branch: String?,
        hotfixBranch: String?,
        sortOrder: Int,
    ): (ComponentConfigurationEntity) -> VcsSettingsEntryEntity =
        { config ->
            VcsSettingsEntryEntity(
                componentConfiguration = config,
                name = name,
                vcsPath = vcsPath,
                branch = branch,
                tag = tag,
                hotfixBranch = hotfixBranch,
                repositoryType = repositoryType,
                sortOrder = sortOrder,
            )
        }

    private fun vcsSettingsBody(): String =
        mvc
            .perform(get("/rest/api/2/components/$COMPONENT/versions/1.0.1/vcs-settings"))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString

    @Test
    @DisplayName("ONB-001 baseline: v2 vcs-settings, single Git root, all fields set, vcsPath lower-cased")
    fun `baseline v2 vcs-settings single git root`() {
        component(
            entry("main", "ssh://git@example.test/Proj/Repo-A.git", "GIT", "test-component-a-1.0", "master", "hotfix/1.0", 0),
        )

        assertEquals(
            """{"versionControlSystemRoots":[""" +
                """{"name":"main","vcsPath":"ssh://git@example.test/proj/repo-a.git","type":"GIT",""" +
                """"tag":"test-component-a-1.0","branch":"master","hotfixBranch":"hotfix/1.0"}""" +
                """],"externalRegistry":null}""",
            vcsSettingsBody(),
        )
    }

    @Test
    @DisplayName("ONB-001 baseline: v2 vcs-settings, multiple roots, NON_NULL omission, default branch, CVS case kept, sort_order")
    fun `baseline v2 vcs-settings multiple roots`() {
        // Inserted out of order: the response follows sort_order.
        component(
            entry("repo-c", "Cvs-Module/Path", "CVS", null, null, null, 2),
            entry("repo-a", "ssh://git@example.test/proj/repo-a.git", "GIT", "test-component-a-1.0", "master", null, 0),
            entry("repo-b", "ssh://git@example.test/Proj/Repo-B.git", null, null, null, null, 1),
        )

        assertEquals(
            """{"versionControlSystemRoots":[""" +
                """{"name":"repo-a","vcsPath":"ssh://git@example.test/proj/repo-a.git","type":"GIT",""" +
                """"tag":"test-component-a-1.0","branch":"master"},""" +
                """{"name":"repo-b","vcsPath":"ssh://git@example.test/proj/repo-b.git","type":"GIT","branch":"master"},""" +
                """{"name":"repo-c","vcsPath":"Cvs-Module/Path","type":"CVS","branch":"HEAD"}""" +
                """],"externalRegistry":null}""",
            vcsSettingsBody(),
        )
    }

    companion object {
        private const val COMPONENT = "test-component-a"
        private const val ALL_VERSIONS = "(,0),[0,)"
    }
}
