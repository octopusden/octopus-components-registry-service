package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentVersionEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactIdRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.repository.FieldOverrideRepository
import org.octopusden.octopus.components.registry.server.repository.JiraComponentConfigRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

class DatabaseComponentRegistryResolverTest {
    private val componentRepository: ComponentRepository = mock()
    private val jiraComponentConfigRepository: JiraComponentConfigRepository = mock()
    private val componentArtifactIdRepository: ComponentArtifactIdRepository = mock()
    private val dependencyMappingRepository: DependencyMappingRepository = mock()
    private val fieldOverrideRepository: FieldOverrideRepository = mock()

    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
        resolver =
            DatabaseComponentRegistryResolver(
                componentRepository,
                jiraComponentConfigRepository,
                componentArtifactIdRepository,
                dependencyMappingRepository,
                fieldOverrideRepository,
                NumericVersionFactory(versionNames),
                VersionRangeFactory(versionNames),
                versionNames,
            )
    }

    @Test
    @DisplayName("MIG-023: DB resolver prefers a version-specific artifact match over generic system artifact")
    fun `MIG-023 DB resolver prefers version-specific artifact over generic match`() {
        val sharedGroup = "com.example.platform"
        val specificArtifactId = "platform_kernel"
        val requestedVersion = "11.1.92"

        val genericComponent = ComponentEntity(name = "system")
        val specificComponent = ComponentEntity(name = specificArtifactId)
        val specificVersion =
            ComponentVersionEntity(
                component = specificComponent,
                versionRange = "[11.1,12.0)",
            )

        val genericArtifact =
            ComponentArtifactIdEntity(
                component = genericComponent,
                groupPattern = sharedGroup,
                artifactPattern = "*",
            )
        val specificArtifact =
            ComponentArtifactIdEntity(
                componentVersion = specificVersion,
                groupPattern = sharedGroup,
                artifactPattern = specificArtifactId,
            )

        `when`(componentArtifactIdRepository.findAll()).thenReturn(listOf(genericArtifact, specificArtifact))

        val resolved =
            resolver.findComponentByArtifact(
                ArtifactDependency(
                    sharedGroup,
                    specificArtifactId,
                    requestedVersion,
                ),
            )

        assertEquals(specificArtifactId, resolved.id)
        assertEquals(requestedVersion, resolved.version)
    }
}
