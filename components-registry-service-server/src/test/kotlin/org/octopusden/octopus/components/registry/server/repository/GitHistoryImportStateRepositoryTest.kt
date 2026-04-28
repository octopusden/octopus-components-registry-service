package org.octopusden.octopus.components.registry.server.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

@SpringBootTest(classes = [ComponentRegistryServiceApplication::class])
@ActiveProfiles("common", "test-db")
class GitHistoryImportStateRepositoryTest {
    @Autowired
    private lateinit var repo: GitHistoryImportStateRepository

    init {
        val testResourcesPath =
            Paths.get(GitHistoryImportStateRepositoryTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `tryInsert returns 1 on first call and 0 on conflict (the 409 gate)`() {
        repo.deleteAll()

        val first = repo.tryInsert("component-history", "refs/tags/x", "deadbeef", "IN_PROGRESS")
        val second = repo.tryInsert("component-history", "refs/tags/other", "cafef00d", "IN_PROGRESS")

        assertEquals(1, first)
        assertEquals(0, second, "second concurrent claim on the same import_key must be denied")

        val existing = repo.findById("component-history").orElseThrow()
        assertEquals("refs/tags/x", existing.targetRef, "first writer's target must be preserved")
        assertEquals("deadbeef", existing.targetSha)
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
