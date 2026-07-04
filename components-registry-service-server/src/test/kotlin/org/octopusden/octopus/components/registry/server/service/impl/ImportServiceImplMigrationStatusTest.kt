package org.octopusden.octopus.components.registry.server.service.impl

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * Unit tests for `ImportServiceImpl.getMigrationStatus`.
 *
 * The migration-status `git` counter is "how many DSL (git-sourced) components are
 * still NOT in the DB". It must be a set difference (git component keys minus
 * db-sourced keys), NOT `gitResolver.size - countBySource("db")`: the subtraction
 * goes negative the moment the DB holds a component the DSL does not (e.g. one
 * created via the v4 write API after migration), which made the panel show `Git -1`
 * and broke every `git == 0` "fully migrated" check (updateCache 410-retirement,
 * the Portal Run gate).
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ImportServiceImplMigrationStatusTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var gitResolver: ComponentRegistryResolverImpl
    private lateinit var componentSourceRepository: ComponentSourceRepository

    @BeforeEach
    fun setUp() {
        gitResolver = mock(ComponentRegistryResolverImpl::class.java)
        componentSourceRepository = mock(ComponentSourceRepository::class.java)
        service = ImportServiceImpl(
            gitResolver = gitResolver,
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = componentSourceRepository,
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
            componentSystemRepository = mock(ComponentSystemRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
            mavenArtifactRepository = mock(DistributionMavenArtifactRepository::class.java),
            componentArtifactMappingRepository = mock(ComponentArtifactMappingRepository::class.java),
            dockerImageRepository = mock(DistributionDockerImageRepository::class.java),
            versionRangeFactory = VersionRangeFactory(VersionNames("serviceCBranch", "serviceC", "minorC")),
            numericVersionFactory = NumericVersionFactory(VersionNames("serviceCBranch", "serviceC", "minorC")),
        )
    }

    // getComponents() is a Java-defined Collection (platform type MutableCollection<EscrowModule!>!),
    // so the stub return value must be a MutableCollection, not a read-only List.
    private fun gitModules(vararg names: String): MutableList<EscrowModule> =
        names.mapTo(mutableListOf()) { name ->
            mock(EscrowModule::class.java).also { Mockito.`when`(it.moduleName).thenReturn(name) }
        }

    private fun stubGit(vararg names: String) {
        // Build (and stub moduleName on) the module mocks BEFORE opening the outer
        // `when(getComponents())` stubbing — nesting Mockito.`when` calls trips
        // UnfinishedStubbingException.
        val modules = gitModules(*names)
        Mockito.`when`(gitResolver.getComponents()).thenReturn(modules)
    }

    private fun stubDb(vararg keys: String) {
        Mockito.`when`(componentSourceRepository.findComponentKeysBySource("db")).thenReturn(keys.toList())
    }

    @Test
    fun `remaining is zero (never negative) when an extra db-only row exceeds the git count`() {
        // Reproduces the reported `Git -1`: every DSL component is migrated AND a
        // later v4-API-created component adds a db-only row, so db (3) > git (2).
        stubGit("a", "b")
        stubDb("a", "b", "x")
        val status = service.getMigrationStatus()
        assertEquals(0L, status.git, "remaining must never go negative when the DB has extra (api-created) rows")
        assertEquals(3L, status.db)
        assertEquals(2L, status.total)
    }

    @Test
    fun `remaining counts git components missing from db even when a db-only row keeps the totals close`() {
        // git: a, b, c ; db: a (migrated) + x (api-created). b, c are still outstanding.
        // The old subtraction would report 3 - 2 = 1; the set difference reports 2.
        stubGit("a", "b", "c")
        stubDb("a", "x")
        val status = service.getMigrationStatus()
        assertEquals(2L, status.git)
        assertEquals(2L, status.db, "db must count all db-sourced rows, including the api-created extra")
        assertEquals(3L, status.total)
    }

    @Test
    fun `remaining is zero and counts match when every git component is migrated`() {
        stubGit("a", "b")
        stubDb("a", "b")
        val status = service.getMigrationStatus()
        assertEquals(0L, status.git)
        assertEquals(2L, status.db)
        assertEquals(2L, status.total)
    }

    @Test
    fun `git resolver failure yields zero git and total (defensive)`() {
        Mockito.`when`(gitResolver.getComponents()).thenThrow(RuntimeException("git unavailable"))
        stubDb("a")
        val status = service.getMigrationStatus()
        assertEquals(0L, status.git)
        assertEquals(1L, status.db)
        assertEquals(0L, status.total)
    }
}
