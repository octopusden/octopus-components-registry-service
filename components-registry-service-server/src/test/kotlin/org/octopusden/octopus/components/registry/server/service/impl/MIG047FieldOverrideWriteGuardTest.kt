package org.octopusden.octopus.components.registry.server.service.impl

import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.octopusden.octopus.components.registry.server.dto.v4.FieldOverrideUpdateRequest
import org.octopusden.octopus.components.registry.server.dto.v4.MarkerChildrenPayload
import org.octopusden.octopus.components.registry.server.dto.v4.MavenArtifactRequest
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.security.CurrentUserResolver
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.springframework.context.ApplicationEventPublisher

/**
 * MIG-047 post-P1-B hardening: V4 write paths must refuse import-internal markers.
 *
 * After `listFieldOverrides` started surfacing GROUP_ARTIFACT_PATTERN rows
 * (commit e847607, the P1-B GREEN), a V4 client can read the row's UUID. The
 * write paths — `updateFieldOverride` / `deleteFieldOverride` — currently only
 * check `row.overriddenAttribute in MarkerAttributes.ALL` to dispatch between
 * marker and scalar update logic. GROUP_ARTIFACT_PATTERN is NOT in `ALL`, so
 * `updateFieldOverride` falls through to the scalar branch, eventually hitting
 * `applyScalarValue` which throws a misleading "Unknown scalar attribute path"
 * error. `deleteFieldOverride` has no guard at all and removes the row outright,
 * silently dropping an import-managed override (recovered only on re-import).
 *
 * These tests pin the desired contract: V4 write paths return a clear,
 * stable error for import-managed marker rows, leaving the row intact.
 *
 * Tests use mocked repositories — no Spring context, no DB. The guards live in
 * service-layer code (`ComponentManagementServiceImpl.updateFieldOverride` /
 * `.deleteFieldOverride`) so the unit test is sufficient.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG047FieldOverrideWriteGuardTest {

    private lateinit var componentRepository: ComponentRepository
    private lateinit var configurationRepository: ComponentConfigurationRepository
    private lateinit var service: ComponentManagementServiceImpl

    private val componentId = UUID.randomUUID()
    private val overrideId = UUID.randomUUID()
    private lateinit var component: ComponentEntity
    private lateinit var markerRow: ComponentConfigurationEntity

    @BeforeEach
    fun setUp() {
        componentRepository = mock(ComponentRepository::class.java)
        configurationRepository = mock(ComponentConfigurationRepository::class.java)

        val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")

        service =
            ComponentManagementServiceImpl(
                componentRepository = componentRepository,
                configurationRepository = configurationRepository,
                componentLabelRepository = mock(ComponentLabelRepository::class.java),
                componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
                componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
                labelRepository = mock(LabelRepository::class.java),
                systemRepository = mock(SystemRepository::class.java),
                toolRepository = mock(ToolRepository::class.java),
                sourceRegistry = mock(ComponentSourceRegistry::class.java),
                applicationEventPublisher = mock(ApplicationEventPublisher::class.java),
                currentUserResolver = mock(CurrentUserResolver::class.java),
                fieldConfigService = mock(FieldConfigService::class.java),
                teamcityProperties = mock(TeamcityProperties::class.java),
                versionRangeFactory = VersionRangeFactory(versionNames),
                environment = mock(org.springframework.core.env.Environment::class.java),
            )

        component = ComponentEntity(id = componentId, componentKey = "alpha-fixture")
        markerRow =
            ComponentConfigurationEntity(
                id = overrideId,
                component = component,
                versionRange = "[1.1,)",
                overriddenAttribute = MarkerAttributes.GROUP_ARTIFACT_PATTERN,
                rowType = "MARKER",
            )

        doReturn(Optional.of(markerRow)).`when`(configurationRepository).findById(overrideId)
    }

    @Test
    @DisplayName(
        "MIG-047 P1-B-W-001: updateFieldOverride rejects GROUP_ARTIFACT_PATTERN row with a clear " +
            "import-managed error and does NOT mutate the row",
    )
    fun mig047_updateFieldOverrideRejectsImportManagedMarker() {
        val request =
            FieldOverrideUpdateRequest(
                versionRange = null,
                value = null,
                markerChildren =
                    MarkerChildrenPayload(
                        mavenArtifacts =
                            listOf(
                                MavenArtifactRequest(
                                    groupPattern = "com.example.malicious",
                                    artifactPattern = "tampered",
                                    extension = null,
                                    classifier = null,
                                ),
                            ),
                    ),
            )

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                service.updateFieldOverride(componentId, overrideId, request)
            }
        assertTrue(
            ex.message!!.contains("import-managed", ignoreCase = true),
            "Error message must identify the row as import-managed; got: '${ex.message}'",
        )
        assertTrue(
            ex.message!!.contains(MarkerAttributes.GROUP_ARTIFACT_PATTERN),
            "Error message must include the marker name for diagnosability; got: '${ex.message}'",
        )

        // No mutation, no save attempt.
        verify(configurationRepository, never()).save(markerRow)
        assertEquals(
            "[1.1,)",
            markerRow.versionRange,
            "Row's versionRange must not have been mutated by the rejected update",
        )
    }

    @Test
    @DisplayName(
        "MIG-047 P1-B-W-002: deleteFieldOverride rejects GROUP_ARTIFACT_PATTERN row " +
            "and does NOT call repository.delete()",
    )
    fun mig047_deleteFieldOverrideRejectsImportManagedMarker() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                service.deleteFieldOverride(componentId, overrideId)
            }
        assertTrue(
            ex.message!!.contains("import-managed", ignoreCase = true),
            "Error message must identify the row as import-managed; got: '${ex.message}'",
        )

        verify(configurationRepository, never()).delete(markerRow)
    }
}
