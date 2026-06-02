package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * MIG-039: `find-by-artifacts` must mirror V1's `EscrowModuleConfigMatcher` —
 * a group/artifact pattern match is necessary but NOT sufficient; the artifact
 * version must also fall within one of the component's configuration version
 * ranges.
 *
 * Bug (live-repro 2026-06-02): for an artifact `<group>:<artifact>:11.1.157` the DB
 * resolver resolved an **archived** component instead of the correct active one.
 * Both carry a `component_artifact_ids` row whose pattern matches `<artifact>`, but
 * the archived one's ranges (`[1.0.x,…)`) exclude `11.1.157`. The DB matcher ignored
 * version ranges and tie-broke by `artifactPattern.length`, so the archived
 * component's long `|`-union pattern won. V1 gates by `versionRange.containsVersion`,
 * so it uniquely picks the in-range (active) component.
 *
 * Mock-based (no Spring / DB), mirroring DatabaseComponentRegistryResolverMavenArtifactsRangeTest.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class DatabaseComponentRegistryResolverFindByArtifactTest {
    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )
    }

    private fun makeComponent(key: String): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun addConfig(component: ComponentEntity, versionRange: String) {
        component.configurations.add(
            ComponentConfigurationEntity(
                component = component,
                versionRange = versionRange,
                overriddenAttribute = null,
                rowType = "BASE",
                deprecated = false,
            ),
        )
    }

    private fun addArtifactId(component: ComponentEntity, groupPattern: String, artifactPattern: String) {
        component.artifactIds.add(
            ComponentArtifactIdEntity(
                component = component,
                groupPattern = groupPattern,
                artifactPattern = artifactPattern,
                sortOrder = component.artifactIds.size,
            ),
        )
    }

    @Test
    @DisplayName("MIG-039: resolves the in-range component, not an archived one whose ranges exclude the version")
    fun `MIG-039 gates artifact resolution by configuration version range`() {
        // Active component: matches 'core' via a |-union pattern (specificity == union, same
        // class as the archived one below, so the tie-break falls through to pattern LENGTH);
        // range contains 11.1.157.
        val active = makeComponent("active-comp")
        addArtifactId(active, "com.example.system", "core|api")
        addConfig(active, "[11.0.0,12.0.0)")

        // Archived component: ALSO matches 'core' via a LONGER |-union pattern (equal specificity,
        // so the old length tie-break preferred it), but its range excludes 11.1.157.
        val archived = makeComponent("archived-comp")
        addArtifactId(archived, "com.example.system", "core|extra|legacy|more|verylongunion")
        addConfig(archived, "[1.0.0,2.0.0)")

        `when`(componentRepository.findAll()).thenReturn(mutableListOf(active, archived))

        val artifact = ArtifactDependency("com.example.system", "core", "11.1.157")
        val resolved = resolver.findComponentsByArtifact(setOf(artifact))[artifact]

        assertNotNull(resolved, "artifact must resolve to the in-range component")
        assertEquals(
            "active-comp",
            resolved!!.id,
            "must pick the version-range-matching component, not the archived one with a longer pattern",
        )
    }

    @Test
    @DisplayName("MIG-039: returns null when no component config range contains the artifact version")
    fun `MIG-039 returns null when version is outside all matching components ranges`() {
        val comp = makeComponent("active-comp")
        addArtifactId(comp, "com.example.system", "core")
        addConfig(comp, "[11.0.0,12.0.0)")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))

        // pattern matches 'core' but 99.0.0 is outside [11.0.0,12.0.0)
        val artifact = ArtifactDependency("com.example.system", "core", "99.0.0")
        assertNull(resolver.findComponentsByArtifact(setOf(artifact))[artifact])
    }

    @Test
    @DisplayName("MIG-039: returns null for an artifact matching no component pattern")
    fun `MIG-039 returns null for an unknown artifact`() {
        val comp = makeComponent("active-comp")
        addArtifactId(comp, "com.example.system", "core")
        addConfig(comp, "[11.0.0,12.0.0)")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))

        val artifact = ArtifactDependency("com.other.group", "totally-unrelated", "11.1.0")
        assertNull(resolver.findComponentsByArtifact(setOf(artifact))[artifact])
    }
}
