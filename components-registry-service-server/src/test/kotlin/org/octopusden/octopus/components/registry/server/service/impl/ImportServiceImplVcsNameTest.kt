package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * Unit tests for `ImportServiceImpl.attachVcsEntries` — verifies that VCS root
 * names are stored verbatim in [VcsSettingsEntryEntity.name] without collapsing.
 *
 * Covers:
 *   - Inline DSL form: `VersionControlSystemRoot` with name `"main"` (the default
 *     produced by the Groovy DSL when vcsUrl/branch are used at module level) →
 *     entity `name = "main"`.
 *   - Named-block single root: custom name key → entity `name = "<key>"`.
 *   - Named-block multi root: two roots with distinct keys → entities preserve
 *     distinct names.
 *
 * `attachVcsEntries` is private; it is exercised via reflection.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ImportServiceImplVcsNameTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var attachVcsEntriesMethod: Method

    @BeforeEach
    fun setUp() {
        // Instantiate ImportServiceImpl with all-mock dependencies.
        // We only exercise the private `attachVcsEntries` method which has
        // no dependency on any injected field.
        service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = mock(EscrowConfigurationLoader::class.java),
            configSyncService = mock(ConfigSyncService::class.java),
            componentRepository = mock(ComponentRepository::class.java),
            configurationRepository = mock(ComponentConfigurationRepository::class.java),
            componentGroupRepository = mock(ComponentGroupRepository::class.java),
            systemRepository = mock(SystemRepository::class.java),
            toolRepository = mock(ToolRepository::class.java),
            labelRepository = mock(LabelRepository::class.java),
            componentLabelRepository = mock(ComponentLabelRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
            mavenArtifactRepository = mock(DistributionMavenArtifactRepository::class.java),
            componentArtifactMappingRepository = mock(ComponentArtifactMappingRepository::class.java),
            dockerImageRepository = mock(DistributionDockerImageRepository::class.java),
            versionRangeFactory = VersionRangeFactory(VersionNames("serviceCBranch", "serviceC", "minorC")),
            numericVersionFactory = NumericVersionFactory(VersionNames("serviceCBranch", "serviceC", "minorC")),
        )

        attachVcsEntriesMethod = ImportServiceImpl::class.java
            .getDeclaredMethod("attachVcsEntries", ComponentConfigurationEntity::class.java, VCSSettings::class.java)
        attachVcsEntriesMethod.isAccessible = true
    }

    private fun callAttachVcsEntries(row: ComponentConfigurationEntity, vcsSettings: VCSSettings?) {
        attachVcsEntriesMethod.invoke(service, row, vcsSettings)
    }

    private fun baseRow(): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = ComponentEntity(id = UUID.randomUUID(), componentKey = "test"),
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
        )

    // =========================================================================

    @Test
    @DisplayName("inline DSL form (root.name = 'main') → entity name = 'main'")
    fun inlineDslForm_entityNameIsMain() {
        // Inline DSL form: VersionControlSystemRoot.create() defaults name to "main".
        val root = VersionControlSystemRoot.create(
            "main",
            RepositoryType.GIT,
            "ssh://git@gitlab:project/repo.git",
            null,
            "main",
            null,
        )
        val vcsSettings = VCSSettings.create(listOf(root))
        val row = baseRow()

        callAttachVcsEntries(row, vcsSettings)

        assertEquals(1, row.vcsEntries.size, "Expected exactly one VCS entry")
        assertEquals("main", row.vcsEntries[0].name, "Inline DSL root.name='main' must be stored as 'main'")
    }

    @Test
    @DisplayName("named-block single root → entity name = configured key")
    fun namedBlockSingleRoot_entityNameIsKey() {
        val configuredKey = "my-component-distribution-vcs"
        val root = VersionControlSystemRoot.create(
            configuredKey,
            RepositoryType.GIT,
            "ssh://git@gitlab:project/repo.git",
            null,
            "main",
            null,
        )
        val vcsSettings = VCSSettings.create(listOf(root))
        val row = baseRow()

        callAttachVcsEntries(row, vcsSettings)

        assertEquals(1, row.vcsEntries.size, "Expected exactly one VCS entry")
        assertEquals(configuredKey, row.vcsEntries[0].name, "Named-block root.name must be stored verbatim")
    }

    @Test
    @DisplayName("named-block multi root → entities preserve distinct names")
    fun namedBlockMultiRoot_entityNamesAreDistinct() {
        val keyA = "cvs"
        val keyB = "mercurial"
        val rootA = VersionControlSystemRoot.create(
            keyA,
            RepositoryType.CVS,
            "OctopusSource/COMPONENT",
            null,
            "MAIN",
            null,
        )
        val rootB = VersionControlSystemRoot.create(
            keyB,
            RepositoryType.MERCURIAL,
            "ssh://hg@mercurial/releng",
            null,
            "v2",
            null,
        )
        val vcsSettings = VCSSettings.create(listOf(rootA, rootB))
        val row = baseRow()

        callAttachVcsEntries(row, vcsSettings)

        assertEquals(2, row.vcsEntries.size, "Expected two VCS entries for multi-root")
        val names = row.vcsEntries.map { it.name }.toSet()
        assertEquals(setOf(keyA, keyB), names, "Multi-root entities must preserve distinct names $keyA and $keyB")
    }
}
