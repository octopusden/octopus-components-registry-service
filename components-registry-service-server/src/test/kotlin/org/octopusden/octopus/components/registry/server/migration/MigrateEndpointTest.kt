package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db")
class MigrateEndpointTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(MigrateEndpointTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `POST migrate runs defaults and components in a single call`() {
        val body =
            mvc
                .perform(post("/rest/api/4/admin/migrate").accept(APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val result: FullMigrationResult = objectMapper.readValue(body)

        // defaults were migrated
        assertFalse(result.defaults.isEmpty(), "defaults map must not be empty")
        assertTrue(result.defaults.containsKey("buildSystem"), "buildSystem must be present in defaults")

        // components were migrated
        assertTrue(result.components.total > 0, "Expected components to be migrated")
        assertEquals(0, result.components.failed, "Expected no migration failures: ${result.components.results.filter { !it.success }}")
        assertEquals(result.components.migrated + result.components.skipped, result.components.total)
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
