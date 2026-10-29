package org.octopusden.octopus.components.registry.server.repository

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/** SYS-104: resync keeps a person's choice, so it asks the audit log whether anyone ever changed `autoRelease`. */
@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Tag("integration")
class AuditLogRepositoryAutoReleaseChangeTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(AuditLogRepositoryAutoReleaseChangeTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun clean() {
        auditLogRepository.deleteAll()
    }

    @Test
    fun `an UPDATE that flipped autoRelease counts`() {
        save("c1", "UPDATE", mapOf("autoRelease" to true, "owner" to "a"), mapOf("autoRelease" to false, "owner" to "a"))

        assertTrue(auditLogRepository.existsAutoReleaseChange("c1"))
    }

    @Test
    fun `a RENAME saved together with an autoRelease change counts`() {
        save("c1", "RENAME", mapOf("autoRelease" to true, "name" to "a"), mapOf("autoRelease" to false, "name" to "b"))

        assertTrue(auditLogRepository.existsAutoReleaseChange("c1"))
    }

    @Test
    fun `other changes, other components and other actions do not count`() {
        save("c1", "UPDATE", mapOf("autoRelease" to false, "owner" to "a"), mapOf("autoRelease" to false, "owner" to "b"))
        save("c1", "UPDATE", mapOf("owner" to "a"), mapOf("owner" to "b"))
        save("c1", "CREATE", null, mapOf("autoRelease" to true))
        save("c2", "UPDATE", mapOf("autoRelease" to false), mapOf("autoRelease" to true))
        save("c1", "UPDATE", mapOf("autoRelease" to false), mapOf("autoRelease" to true), entityType = "Label")

        assertFalse(auditLogRepository.existsAutoReleaseChange("c1"))
    }

    private fun save(
        entityId: String,
        action: String,
        oldValue: Map<String, Any?>?,
        newValue: Map<String, Any?>?,
        entityType: String = "Component",
    ) {
        auditLogRepository.save(
            AuditLogEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                source = "api",
                oldValue = oldValue,
                newValue = newValue,
            ),
        )
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
