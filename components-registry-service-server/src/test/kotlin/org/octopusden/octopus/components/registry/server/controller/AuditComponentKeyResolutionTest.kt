package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.AuditLogFilter
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.service.AuditService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageRequest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-054 — `AuditLogResponse.componentKey` resolution branches that the live
 * field-override lookup (covered in `FieldOverrideAuditTest`) does not exercise:
 * the snapshot `name` / `moduleName` fallback for components that no longer
 * exist, blank-name normalization to null, and null for non-Component rows.
 *
 * Drives `AuditService.getRecentChanges` directly against synthetic audit rows
 * (random UUIDs absent from the components table, so the live lookup misses and
 * the fallback path runs deterministically).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class AuditComponentKeyResolutionTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var auditService: AuditService

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        // Same bootstrap as the other ft-db integration tests: the context binds
        // components-registry.groovy-path from this env placeholder.
        val testResourcesPath =
            Paths.get(AuditComponentKeyResolutionTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-054: a deleted component (absent from the repo) falls back to the snapshot name")
    fun `SYS-054 deleted component falls back to snapshot name`() {
        val entityId = UUID.randomUUID().toString()
        saveRow("Component", entityId, "DELETE", oldValue = mapOf("name" to "ghost-comp", "archived" to true))

        assertEquals("ghost-comp", resolvedKey(entityId))
    }

    @Test
    @DisplayName("SYS-054: a MIGRATED row with no name falls back to the snapshot moduleName")
    fun `SYS-054 migrated row falls back to snapshot moduleName`() {
        val entityId = UUID.randomUUID().toString()
        saveRow(
            "Component",
            entityId,
            AuditLogEntity.ACTION_MIGRATED,
            newValue = mapOf("moduleName" to "legacy-module", "moduleConfigurations" to emptyList<Any>()),
        )

        assertEquals("legacy-module", resolvedKey(entityId))
    }

    @Test
    @DisplayName("SYS-054: a blank snapshot name resolves to null, not an empty key")
    fun `SYS-054 blank snapshot name resolves to null`() {
        val entityId = UUID.randomUUID().toString()
        saveRow("Component", entityId, "UPDATE", newValue = mapOf("name" to "   "))

        assertNull(resolvedKey(entityId))
    }

    @Test
    @DisplayName("SYS-054: non-Component rows resolve componentKey to null")
    fun `SYS-054 non-component rows resolve to null`() {
        val entityId = "non-component-entity-id"
        saveRow("FieldDefinition", entityId, "UPDATE", newValue = mapOf("token" to "x"))

        assertNull(resolvedKey(entityId))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun saveRow(
        entityType: String,
        entityId: String,
        action: String,
        oldValue: Map<String, Any?>? = null,
        newValue: Map<String, Any?>? = null,
    ) {
        auditLogRepository.save(
            AuditLogEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                oldValue = oldValue,
                newValue = newValue,
            ),
        )
    }

    /** componentKey of the single audit row carrying [entityId] (includeMigrated so MIGRATED is visible). */
    private fun resolvedKey(entityId: String): String? =
        auditService
            .getRecentChanges(AuditLogFilter(entityId = entityId, includeMigrated = true), PageRequest.of(0, 10))
            .content
            .single()
            .componentKey
}
