package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactMappingEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactMappingRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSystemRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionDockerImageRepository
import org.octopusden.octopus.components.registry.server.repository.DistributionMavenArtifactRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * ARTGRP import-side canonicalization: `buildOwnershipMappings` must split a comma group-list
 * into ONE row per Maven groupId for EVERY mode, so the migrated `component_artifact_mappings`
 * table never stores a comma in `group_pattern`. (ALL_EXCEPT_CLAIMED already split; EXPLICIT/ALL
 * previously kept the comma-list as a single row — the RED case here.) The split is semantically
 * safe: each row shares the same mode/tokens/range, and the forward wire re-composes them.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ArtifactGroupSplitImportTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var buildOwnershipMappings: Method

    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")

    @BeforeEach
    fun setUp() {
        val configurationLoader = mock(EscrowConfigurationLoader::class.java)
        service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = configurationLoader,
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
            versionRangeFactory = VersionRangeFactory(versionNames),
            numericVersionFactory = NumericVersionFactory(versionNames),
        )
        buildOwnershipMappings = ImportServiceImpl::class.java.getDeclaredMethod(
            "buildOwnershipMappings",
            ComponentEntity::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    private fun build(
        group: String?,
        artifact: String?,
        startSortOrder: Int = 0,
        range: String = "(,0),[0,)",
    ): List<ComponentArtifactMappingEntity> {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "artgrp-import-fixture")
        return buildOwnershipMappings.invoke(service, component, range, group, artifact, startSortOrder)
            as List<ComponentArtifactMappingEntity>
    }

    @Test
    @DisplayName("ARTGRP-IMP-001: an EXPLICIT comma group-list is split into one comma-free row per groupId, same tokens")
    fun `ARTGRP-IMP-001 explicit comma group split into one row per group`() {
        val mappings = build("grp-alfa,grp-beta", "widget")

        assertEquals(2, mappings.size, "comma group-list must split into one mapping per groupId")
        assertEquals(listOf("grp-alfa", "grp-beta"), mappings.map { it.groupPattern }, "each row holds exactly one groupId")
        assertTrue(mappings.none { it.groupPattern.contains(",") }, "no stored group_pattern may contain a comma")
        assertTrue(mappings.all { it.artifactIdMode == ArtifactIdMode.EXPLICIT.name }, "mode preserved across the split")
        assertTrue(
            mappings.all { it.tokens.map { t -> t.artifactPattern } == listOf("widget") },
            "the shared artifact token set is copied to every split row",
        )
        assertEquals(listOf(0, 1), mappings.map { it.sortOrder }, "sortOrder runs from startSortOrder across the split")
    }

    @Test
    @DisplayName("ARTGRP-IMP-002: startSortOrder offsets the split rows (multi-config components stay contiguous)")
    fun `ARTGRP-IMP-002 split rows honour startSortOrder`() {
        val mappings = build("grp-alfa,grp-beta", "widget", startSortOrder = 5)
        assertEquals(listOf(5, 6), mappings.map { it.sortOrder })
    }

    @Test
    @DisplayName("ARTGRP-IMP-003 (guard): a single-group EXPLICIT mapping stays one row (split is a no-op)")
    fun `ARTGRP-IMP-003 single group stays one row`() {
        val mappings = build("grp-alfa", "widget")
        assertEquals(1, mappings.size)
        assertEquals("grp-alfa", mappings.single().groupPattern)
    }

    @Test
    @DisplayName("ARTGRP-IMP-004: a malformed comma group-list (empty segment) fails loud, not silently dropped")
    fun `ARTGRP-IMP-004 empty group segment fails loud`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException::class.java,
        ) { build("grp-alfa,,grp-beta", "widget") }
        assertTrue(ex.cause is IllegalArgumentException, "must fail loud on the empty segment: ${ex.cause}")
    }
}
