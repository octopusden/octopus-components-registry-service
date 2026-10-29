package org.octopusden.octopus.components.registry.server.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
@Tag("integration")
class AuditLogRepositoryChangeStatsTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(AuditLogRepositoryChangeStatsTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `changeStats on an empty table is zero-zero`() {
        auditLogRepository.deleteAll()

        val stats = auditLogRepository.changeStats()

        assertEquals(0L, stats.maxId, "empty table must COALESCE MAX(id) to 0, not null")
        assertEquals(0L, stats.count, "empty table has no rows to count")
    }

    @Test
    fun `changeStats excludes git-history rows from both fields`() {
        auditLogRepository.deleteAll()

        auditLogRepository.save(newRow("Component", "a", "MIGRATED", source = "git-history"))
        auditLogRepository.save(newRow("Component", "b", "MIGRATED", source = "git-history"))

        val stats = auditLogRepository.changeStats()

        assertEquals(0L, stats.maxId, "git-history rows must be excluded from maxId")
        assertEquals(0L, stats.count, "git-history rows must be excluded from count")
    }

    @Test
    fun `changeStats counts every non-git-history row regardless of id ordering`() {
        auditLogRepository.deleteAll()

        auditLogRepository.save(newRow("Component", "a", "CREATE", source = "api"))
        auditLogRepository.save(newRow("Component", "b", "MIGRATED", source = "git-history"))
        auditLogRepository.save(newRow("Component", "c", "UPDATE", source = "api"))
        auditLogRepository.save(newRow("Label", "d", "CREATE", source = "portal"))

        val stats = auditLogRepository.changeStats()

        assertEquals(3L, stats.count, "count must include every non-git-history row")
    }

    @Test
    fun `changeStats advances maxId on delete-then-reinsert while count holds`() {
        auditLogRepository.deleteAll()

        auditLogRepository.save(newRow("Component", "a", "CREATE", source = "api"))
        val doomed = auditLogRepository.save(newRow("Component", "b", "CREATE", source = "api"))

        val before = auditLogRepository.changeStats()
        assertEquals(2L, before.count)

        auditLogRepository.delete(doomed)
        auditLogRepository.save(newRow("Component", "c", "CREATE", source = "api"))

        val after = auditLogRepository.changeStats()

        assertEquals(before.count, after.count, "delete-then-reinsert leaves count unchanged")
        assertTrue(
            after.maxId > before.maxId,
            "maxId must advance on reinsert (before=${before.maxId}, after=${after.maxId})",
        )
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
