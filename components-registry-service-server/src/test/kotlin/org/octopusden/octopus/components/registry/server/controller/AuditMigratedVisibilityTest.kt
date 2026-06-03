package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * SYS-049 — git-history baseline rows are written with the `MIGRATED` action and
 * are hidden from the audit views by default. Both audit read endpoints accept an
 * `includeMigrated` flag (default `false`) to opt back in, and an explicit
 * `action=MIGRATED` filter always returns them so the Portal "Show migration"
 * toggle can surface them on demand.
 *
 * Integration test (ft-db = H2 + auto-migrate). Rows are seeded directly through
 * the repository so action/source can be pinned without running a real Git import.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class AuditMigratedVisibilityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(AuditMigratedVisibilityTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-049: audit/recent hides MIGRATED rows by default")
    fun `SYS-049 recent hides MIGRATED by default`() {
        val tag = "sys049_recent_${UUID.randomUUID().toString().take(8)}"
        seedRow(entityId = "${tag}_create", action = "CREATE", source = "api")
        seedRow(entityId = "${tag}_migrated", action = "MIGRATED", source = "git-history")

        val actions = recentActionsForTag(tag)
        assertTrue(actions.contains("CREATE"), "expected the CREATE row to be visible by default, got $actions")
        assertFalse(actions.contains("MIGRATED"), "expected MIGRATED rows to be hidden by default, got $actions")
    }

    @Test
    @DisplayName("SYS-049: audit/recent?includeMigrated=true surfaces MIGRATED rows")
    fun `SYS-049 recent includeMigrated returns MIGRATED`() {
        val tag = "sys049_incl_${UUID.randomUUID().toString().take(8)}"
        seedRow(entityId = "${tag}_create", action = "CREATE", source = "api")
        seedRow(entityId = "${tag}_migrated", action = "MIGRATED", source = "git-history")

        val actions = recentActionsForTag(tag, includeMigrated = true)
        assertTrue(actions.contains("CREATE"), "expected CREATE row, got $actions")
        assertTrue(actions.contains("MIGRATED"), "expected MIGRATED row when includeMigrated=true, got $actions")
    }

    @Test
    @DisplayName("SYS-049: explicit action=MIGRATED returns MIGRATED rows even without includeMigrated")
    fun `SYS-049 explicit action MIGRATED wins over default hide`() {
        val tag = "sys049_explicit_${UUID.randomUUID().toString().take(8)}"
        seedRow(entityId = "${tag}_migrated", action = "MIGRATED", source = "git-history")

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("action", "MIGRATED")
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val migratedIds =
            objectMapper.readTree(body)["content"]
                .filter { it["entityId"].asText().startsWith(tag) }
                .map { it["entityId"].asText() }
                .toSet()
        assertEquals(
            setOf("${tag}_migrated"),
            migratedIds,
            "explicit action=MIGRATED must return the MIGRATED row, got $migratedIds",
        )
    }

    @Test
    @DisplayName("SYS-049: entity history hides MIGRATED by default but includeMigrated=true surfaces it")
    fun `SYS-049 entity history honours includeMigrated`() {
        val entityId = "sys049_entity_${UUID.randomUUID()}"
        seedRow(entityId = entityId, action = "MIGRATED", source = "git-history")
        seedRow(entityId = entityId, action = "UPDATE", source = "api")

        val defaultActions = entityHistoryActions(entityId)
        assertEquals(
            setOf("UPDATE"),
            defaultActions,
            "entity history must hide MIGRATED by default, got $defaultActions",
        )

        val withMigrated = entityHistoryActions(entityId, includeMigrated = true)
        assertEquals(
            setOf("UPDATE", "MIGRATED"),
            withMigrated,
            "entity history with includeMigrated=true must include MIGRATED, got $withMigrated",
        )
    }

    private fun recentActionsForTag(
        tag: String,
        includeMigrated: Boolean = false,
    ): Set<String> {
        val request =
            get("/rest/api/4/audit/recent")
                .with(adminJwt())
                .param("size", "500")
        if (includeMigrated) request.param("includeMigrated", "true")
        val body =
            mvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["content"]
            .filter { it["entityId"].asText().startsWith(tag) }
            .map { it["action"].asText() }
            .toSet()
    }

    private fun entityHistoryActions(
        entityId: String,
        includeMigrated: Boolean = false,
    ): Set<String> {
        val request =
            get("/rest/api/4/audit/Component/$entityId")
                .with(adminJwt())
                .param("size", "500")
        if (includeMigrated) request.param("includeMigrated", "true")
        val body =
            mvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["content"]
            .map { it["action"].asText() }
            .toSet()
    }

    private fun seedRow(
        entityId: String,
        action: String,
        source: String,
    ): AuditLogEntity =
        auditLogRepository.save(
            AuditLogEntity(
                entityType = "Component",
                entityId = entityId,
                action = action,
                changedBy = "system",
                source = source,
            ),
        )
}
