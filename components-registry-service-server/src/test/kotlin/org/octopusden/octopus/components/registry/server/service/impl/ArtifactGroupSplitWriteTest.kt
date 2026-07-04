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
import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
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

/**
 * ARTGRP write-side canonicalization: the v4 create/update `artifactIds` path must split a comma
 * group-list into ONE mapping per Maven groupId, with a RUNNING sort-order across all requests
 * (one request can now expand to N rows, so `sortOrder = requestIndex` would collide / reorder —
 * and the resolver treats the lowest sortOrder as the legacy primary). New API writes therefore
 * never re-introduce a comma into `group_pattern`.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ArtifactGroupSplitWriteTest {

    private lateinit var service: ComponentManagementServiceImpl
    private lateinit var addArtifactIds: Method

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
        )
        addArtifactIds = ComponentManagementServiceImpl::class.java.getDeclaredMethod(
            "addArtifactIds",
            ComponentEntity::class.java,
            List::class.java,
        ).apply { isAccessible = true }
    }

    private fun component() = ComponentEntity(id = UUID.randomUUID(), componentKey = "artgrp-write-fixture")

    private fun apply(component: ComponentEntity, vararg requests: ArtifactIdRequest) {
        addArtifactIds.invoke(service, component, requests.toList())
    }

    @Test
    @DisplayName("ARTGRP-WR-001: a comma-group EXPLICIT request splits into one comma-free mapping per groupId, same tokens")
    fun `ARTGRP-WR-001 comma group request splits into per-group mappings`() {
        val c = component()
        apply(c, ArtifactIdRequest(groupPattern = "grp-alfa,grp-beta", mode = "EXPLICIT", artifactTokens = listOf("widget")))

        assertEquals(2, c.artifactMappings.size, "one mapping per groupId")
        assertEquals(listOf("grp-alfa", "grp-beta"), c.artifactMappings.map { it.groupPattern })
        assertTrue(c.artifactMappings.none { it.groupPattern.contains(",") }, "no stored group_pattern may contain a comma")
        assertTrue(c.artifactMappings.all { it.artifactIdMode == ArtifactIdMode.EXPLICIT.name })
        assertTrue(c.artifactMappings.all { it.tokens.map { t -> t.artifactPattern } == listOf("widget") })
        assertEquals(listOf(0, 1), c.artifactMappings.map { it.sortOrder })
    }

    @Test
    @DisplayName("ARTGRP-WR-002: sortOrder is a RUNNING counter across requests when one request expands to N rows")
    fun `ARTGRP-WR-002 running sort order across expanding requests`() {
        val c = component()
        apply(
            c,
            ArtifactIdRequest(groupPattern = "grp-alfa,grp-beta", mode = "EXPLICIT", artifactTokens = listOf("w1")),
            ArtifactIdRequest(groupPattern = "grp-gamma", mode = "EXPLICIT", artifactTokens = listOf("w2")),
        )

        assertEquals(3, c.artifactMappings.size, "2 (split) + 1 = 3 mappings")
        assertEquals(listOf("grp-alfa", "grp-beta", "grp-gamma"), c.artifactMappings.map { it.groupPattern })
        assertEquals(
            listOf(0, 1, 2),
            c.artifactMappings.map { it.sortOrder },
            "sortOrder must be a running counter, not the request index (which would collide at 0,0,1)",
        )
    }
}
