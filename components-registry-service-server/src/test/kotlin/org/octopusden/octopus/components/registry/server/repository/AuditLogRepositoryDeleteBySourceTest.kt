package org.octopusden.octopus.components.registry.server.repository

import org.junit.jupiter.api.Assertions.assertEquals
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

@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
class AuditLogRepositoryDeleteBySourceTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(AuditLogRepositoryDeleteBySourceTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `deleteBySource removes only rows with matching source`() {
        auditLogRepository.deleteAll()

        auditLogRepository.save(newRow("Component", "a", "CREATE", source = "api"))
        auditLogRepository.save(newRow("Component", "b", "UPDATE", source = "git-history"))
        auditLogRepository.save(newRow("Component", "c", "DELETE", source = "git-history"))

        val deleted = auditLogRepository.deleteBySource("git-history")

        assertEquals(2, deleted)
        val remaining = auditLogRepository.findAll()
        assertEquals(1, remaining.size)
        assertEquals("api", remaining.single().source)
    }

    private fun newRow(
        entityType: String,
        entityId: String,
        action: String,
        source: String,
    ) = AuditLogEntity(
        entityType = entityType,
        entityId = entityId,
        action = action,
        source = source,
    )

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
