package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.dto.v4.ArtifactIdRequest
import org.octopusden.octopus.components.registry.server.entity.ArtifactIdMode
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
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
        addArtifactIds = ComponentManagementServiceImpl::class.java
            .getDeclaredMethod(
                "addArtifactIds",
                ComponentEntity::class.java,
                List::class.java,
                Set::class.java,
            ).apply { isAccessible = true }
    }

    private fun component() = ComponentEntity(id = UUID.randomUUID(), componentKey = "artgrp-write-fixture")

    private fun apply(
        component: ComponentEntity,
        vararg requests: ArtifactIdRequest,
    ) {
        applyWithCurrentRanges(component, emptySet(), *requests)
    }

    private fun applyWithCurrentRanges(
        component: ComponentEntity,
        currentOwnershipRanges: Set<String>,
        vararg requests: ArtifactIdRequest,
    ) {
        addArtifactIds.invoke(service, component, requests.toList(), currentOwnershipRanges)
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
    @DisplayName("ARTGRP-WR-003: a malformed comma group-list (empty segment) is rejected, not silently normalized")
    fun `ARTGRP-WR-003 empty group segment is rejected`() {
        val c = component()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException::class.java,
        ) {
            apply(c, ArtifactIdRequest(groupPattern = "grp-alfa,,grp-beta", mode = "EXPLICIT", artifactTokens = listOf("widget")))
        }
        assertTrue(ex.cause is IllegalArgumentException, "must fail with a 400-mapped IllegalArgumentException")
        assertTrue(
            (ex.cause?.message ?: "").contains("empty groupId segment"),
            "message must name the empty-segment cause: ${ex.cause?.message}",
        )
        assertTrue(c.artifactMappings.isEmpty(), "no mappings may be persisted from a rejected request")
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

    @Test
    @DisplayName("SYS-108 / ARTGRP-WR-004: an override range that is neither a configuration nor a current ownership range is rejected")
    fun `SYS-108 ARTGRP-WR-004 unknown override range is rejected`() {
        val c = component()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException::class.java,
        ) {
            apply(
                c,
                ArtifactIdRequest(groupPattern = "grp-alfa", mode = "EXPLICIT", artifactTokens = listOf("w1"), versionRange = OLD_BLOCK),
            )
        }
        assertTrue(ex.cause is IllegalArgumentException, "must fail with a 400-mapped IllegalArgumentException")
        assertTrue(
            (ex.cause?.message ?: "").contains("versionRange '$OLD_BLOCK'"),
            "message must name the rejected range: ${ex.cause?.message}",
        )
        assertTrue(c.artifactMappings.isEmpty(), "no mappings may be persisted from a rejected request")
    }

    @Test
    @DisplayName("SYS-108 / ARTGRP-WR-005: a REPLACE may re-send an ownership range the component already has without a configuration row")
    fun `SYS-108 ARTGRP-WR-005 current ownership range without configuration row is accepted`() {
        // Migration shape: a DSL block that differs from the base only in groupId/artifactId becomes an
        // ownership override with NO configuration row at its range; the component must still be able to
        // re-save its ownership (e.g. to drop one token from that block).
        val c = component()
        applyWithCurrentRanges(
            c,
            setOf(OLD_BLOCK),
            ArtifactIdRequest(groupPattern = "grp-alfa", mode = "EXPLICIT", artifactTokens = listOf("w1")),
            ArtifactIdRequest(groupPattern = "grp-alfa", mode = "EXPLICIT", artifactTokens = listOf("w2"), versionRange = OLD_BLOCK),
        )

        assertEquals(listOf("(,0),[0,)", OLD_BLOCK), c.artifactMappings.map { it.versionRange })
        val overrideTokens = c.artifactMappings.last().tokens
        assertEquals(listOf("w2"), overrideTokens.map { it.artifactPattern })
    }

    @Test
    @DisplayName("SYS-108 / ARTGRP-WR-006: a current ownership range matches whitespace-insensitively and keeps its stored form")
    fun `SYS-108 ARTGRP-WR-006 spaced migrated range matches its unspaced form`() {
        // DSL-migrated ranges keep the space after the comma; a client may send the range without it.
        val stored = "[1.0.36-0000, 1.0.37-0000)"
        val c = component()
        applyWithCurrentRanges(
            c,
            setOf(stored),
            ArtifactIdRequest(groupPattern = "grp-alfa", mode = "EXPLICIT", artifactTokens = listOf("w1"), versionRange = OLD_BLOCK),
        )

        assertEquals(listOf(stored), c.artifactMappings.map { it.versionRange })
    }

    @Test
    @DisplayName("SYS-108 / ARTGRP-WR-007: a current ownership spelling wins over a configuration range spelled differently")
    fun `SYS-108 ARTGRP-WR-007 ownership spelling wins over configuration spelling`() {
        val stored = "[1.0.36-0000, 1.0.37-0000)"
        val c = component()
        c.configurations.add(configRow(c, OLD_BLOCK))
        applyWithCurrentRanges(
            c,
            setOf(stored),
            ArtifactIdRequest(groupPattern = "grp-alfa", mode = "EXPLICIT", artifactTokens = listOf("w1"), versionRange = OLD_BLOCK),
        )

        assertEquals(listOf(stored), c.artifactMappings.map { it.versionRange })
    }

    @Test
    @DisplayName("SYS-108 / ARTGRP-WR-008: create matches a configuration range or the base ignoring whitespace and stores their form")
    fun `SYS-108 ARTGRP-WR-008 create matches ranges ignoring whitespace`() {
        val c = component()
        c.configurations.add(configRow(c, OLD_BLOCK))
        apply(
            c,
            ArtifactIdRequest(groupPattern = "grp-alfa", mode = "EXPLICIT", artifactTokens = listOf("w1"), versionRange = "(, 0),[0,)"),
            ArtifactIdRequest(
                groupPattern = "grp-alfa",
                mode = "EXPLICIT",
                artifactTokens = listOf("w2"),
                versionRange = "[1.0.36-0000, 1.0.37-0000)",
            ),
        )

        assertEquals(listOf("(,0),[0,)", OLD_BLOCK), c.artifactMappings.map { it.versionRange })
    }

    private fun configRow(
        c: ComponentEntity,
        range: String,
    ) = ComponentConfigurationEntity(component = c, versionRange = range, rowType = "BASE")

    private companion object {
        const val OLD_BLOCK = "[1.0.36-0000,1.0.37-0000)"
    }
}
