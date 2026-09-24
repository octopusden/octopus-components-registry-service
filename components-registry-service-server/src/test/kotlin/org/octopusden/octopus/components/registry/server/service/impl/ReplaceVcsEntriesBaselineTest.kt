package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.dto.v4.VcsEntryRequest
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingTokenRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.TeamcityProjectRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.octopus.components.registry.server.util.ComponentCodeRenderer
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ONB-001 baseline (characterization): how the v4 write path stores VCS entries. `replaceVcsEntries`
 * backs every VCS write (create, PATCH with `vcsEntries`, `vcs.settings` field overrides), so it is
 * driven directly over an in-memory configuration row.
 *
 * Pinned: a missing request `name` is stored as `"main"`, with no uniqueness check (two unnamed entries
 * both become `"main"`); the list is replaced wholesale on every write (prior entity instances are
 * dropped and new ones carry no id, so the DB assigns fresh ids through `orphanRemoval` + generated
 * UUIDs); `sortOrder` is the request list index.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ReplaceVcsEntriesBaselineTest {
    private lateinit var service: ComponentManagementServiceImpl
    private lateinit var replaceVcsEntries: Method

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")

    @BeforeEach
    fun setUp() {
        service = ComponentManagementServiceImpl(
            componentRepository = mock(ComponentRepository::class.java),
            configurationRepository = mock(ComponentConfigurationRepository::class.java),
            componentLabelRepository = mock(ComponentLabelRepository::class.java),
            componentSystemRepository = mock(ComponentSystemRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
            mavenArtifactRepository = mock(DistributionMavenArtifactRepository::class.java),
            componentArtifactMappingRepository = mock(ComponentArtifactMappingRepository::class.java),
            componentArtifactMappingTokenRepository = mock(ComponentArtifactMappingTokenRepository::class.java),
            dockerImageRepository = mock(DistributionDockerImageRepository::class.java),
            labelRepository = mock(LabelRepository::class.java),
            systemRepository = mock(SystemRepository::class.java),
            teamcityProjectRepository = mock(TeamcityProjectRepository::class.java),
            toolRepository = mock(ToolRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            applicationEventPublisher = mock(ApplicationEventPublisher::class.java),
            currentUserResolver = mock(CurrentUserResolver::class.java),
            fieldConfigService = mock(FieldConfigService::class.java),
            permissionEvaluator = mock(PermissionEvaluator::class.java),
            teamcityProperties = mock(TeamcityProperties::class.java),
            versionRangeFactory = VersionRangeFactory(versionNames),
            numericVersionFactory = NumericVersionFactory(versionNames),
            environment = mock(Environment::class.java),
            componentCodeRenderer = mock(ComponentCodeRenderer::class.java),
            employeeDirectory = mock(EmployeeDirectoryService::class.java),
            transactionManager = mock(PlatformTransactionManager::class.java),
        )
        replaceVcsEntries = ComponentManagementServiceImpl::class.java
            .getDeclaredMethod(
                "replaceVcsEntries",
                ComponentConfigurationEntity::class.java,
                List::class.java,
            ).apply { isAccessible = true }
    }

    private fun baseRow() =
        ComponentConfigurationEntity(
            component = ComponentEntity(id = UUID.randomUUID(), componentKey = "test-component-a"),
            versionRange = "(,0),[0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

    private fun write(
        config: ComponentConfigurationEntity,
        vararg requests: VcsEntryRequest,
    ) {
        replaceVcsEntries.invoke(service, config, requests.toList())
    }

    @Test
    @DisplayName("ONB-001 baseline: v4 VCS entry without name is stored as \"main\"; an explicit name is kept")
    fun `baseline missing name is stored as main`() {
        val config = baseRow()
        write(
            config,
            VcsEntryRequest(vcsPath = REPO_A),
            VcsEntryRequest(name = "repo-b", vcsPath = REPO_B),
        )

        assertEquals(listOf("main", "repo-b"), config.vcsEntries.map { it.name })
    }

    @Test
    @DisplayName("ONB-001 baseline: two unnamed v4 VCS entries are both stored as \"main\" (duplicate accepted)")
    fun `baseline two unnamed entries both become main`() {
        val config = baseRow()
        write(config, VcsEntryRequest(vcsPath = REPO_A), VcsEntryRequest(vcsPath = REPO_B))

        assertEquals(listOf("main", "main"), config.vcsEntries.map { it.name })
        assertEquals(listOf(REPO_A, REPO_B), config.vcsEntries.map { it.vcsPath })
    }

    @Test
    @DisplayName("ONB-001 baseline: every v4 VCS write recreates all entries; sortOrder is the request index")
    fun `baseline every write recreates entries in request order`() {
        val config = baseRow()
        val stored =
            VcsSettingsEntryEntity(id = UUID.randomUUID(), componentConfiguration = config, name = "repo-a", vcsPath = REPO_A)
        config.vcsEntries.add(stored)

        // Same repository re-sent unchanged, plus a second one ahead of it.
        write(
            config,
            VcsEntryRequest(name = "repo-b", vcsPath = REPO_B, branch = "master"),
            VcsEntryRequest(name = "repo-a", vcsPath = REPO_A),
        )

        assertEquals(listOf(REPO_B, REPO_A), config.vcsEntries.map { it.vcsPath })
        assertEquals(listOf(0, 1), config.vcsEntries.map { it.sortOrder })
        assertTrue(config.vcsEntries.none { it === stored }, "the stored entity is dropped, not updated in place")
        config.vcsEntries.forEach { assertNull(it.id, "a recreated entry carries no id; the DB assigns a new one") }
    }

    companion object {
        private const val REPO_A = "ssh://git@example.test/proj/repo-a.git"
        private const val REPO_B = "ssh://git@example.test/proj/repo-b.git"
    }
}
