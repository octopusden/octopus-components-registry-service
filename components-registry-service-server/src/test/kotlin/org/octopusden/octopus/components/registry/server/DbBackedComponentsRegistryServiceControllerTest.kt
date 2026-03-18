package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * Runs ALL 25 resolver-behavior tests (RES-001..RES-025) from
 * [BaseComponentsRegistryServiceTest] against the **DB backend** after migration.
 *
 * Mirrors [ComponentsRegistryServiceControllerTest] which runs the same tests
 * against the Git backend.
 *
 * @see docs/db-migration/requirements-resolver.md
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbBackedComponentsRegistryServiceControllerTest : MockMvcRegistryTestSupport() {
    @Autowired
    private lateinit var sourceRegistry: ComponentSourceRegistry

    init {
        val testResourcesUri =
            DbBackedComponentsRegistryServiceControllerTest::class.java
                .getResource("/expected-data")!!
                .toURI()
        val testResourcesPath = Paths.get(testResourcesUri).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeAll
    fun migrateAllToDb() {
        // Step 1: migrate defaults
        mvc
            .perform(post("/rest/api/4/admin/migrate-defaults").accept(APPLICATION_JSON))
            .andExpect(status().isOk)

        // Step 2: migrate all components
        val resultJson =
            mvc
                .perform(
                    post("/rest/api/4/admin/migrate-components").accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val migrationResult = objectMapper.readTree(resultJson)
        assertTrue(
            migrationResult.path("migrated").asInt() > 0,
            "Expected components to be migrated, got: $migrationResult",
        )
        assertEquals(
            0,
            migrationResult.path("failed").asInt(),
            "Expected no migration failures: ${migrationResult.path("results").filter { !it.path("success").asBoolean() }}",
        )

        // Step 3: discover all component names and switch to DB source
        val componentsJson =
            mvc
                .perform(
                    get("/rest/api/2/components").accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val tree = objectMapper.readTree(componentsJson)
        val componentNames =
            tree
                .path("components")
                .map { it.path("id").asText() }
                .filter { it.isNotEmpty() }

        for (name in componentNames) {
            sourceRegistry.setComponentSource(name, "db")
        }
    }

    companion object {
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
