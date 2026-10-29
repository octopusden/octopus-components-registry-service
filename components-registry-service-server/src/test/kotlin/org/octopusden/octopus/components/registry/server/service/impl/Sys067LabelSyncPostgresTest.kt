package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-067 on real PostgreSQL (Testcontainers).
 *
 * The production incident that motivated SYS-067 happened on PostgreSQL. The defect is at the
 * Hibernate persistence-context level (engine-agnostic) and is fully covered on H2 by
 * [Sys067JunctionSyncPreservesExistingTest]; this test pins the same label path against the real
 * engine so the guard is faithful to where the incident occurred. Kept to the label path (the one
 * observed in production) — systems and required-tools share the identical code path and are covered
 * on H2. Asserts persisted junction rows, not the response projection.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
@Tag("integration")
class Sys067LabelSyncPostgresTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var componentLabelRepository: ComponentLabelRepository

    init {
        val testResourcesPath =
            Paths
                .get(Sys067LabelSyncPostgresTest::class.java.getResource("/expected-data")!!.toURI())
                .parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeAll
    fun migrateAllToDb() {
        mvc
            .perform(post("/rest/api/4/admin/migrate-defaults").with(adminJwt()).accept(APPLICATION_JSON))
            .andExpect(status().isOk)
        val result =
            mvc
                .perform(post("/rest/api/4/admin/migrate-components").with(adminJwt()).accept(APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertTrue(
            objectMapper.readTree(result).path("migrated").asInt() > 0,
            "expected components to be migrated into Postgres, got: $result",
        )
    }

    private fun firstComponentId(): String {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(editorJwt()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).path("content")[0]["id"].asText()
    }

    private fun getComponent(id: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun patchLabels(
        id: String,
        labels: List<String>,
    ) {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to getComponent(id)["version"].asLong(), "labels" to labels),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("SYS-067: adding a label preserves the existing labels on PostgreSQL")
    fun `SYS-067 adding a label preserves the existing labels on PostgreSQL`() {
        val id = firstComponentId()
        val keep = "keep-${UUID.randomUUID()}"
        val add = "add-${UUID.randomUUID()}"

        patchLabels(id, listOf(keep))
        patchLabels(id, listOf(keep, add))

        assertEquals(
            setOf(keep, add),
            componentLabelRepository.findByComponentId(UUID.fromString(id)).map { it.labelCode }.toSet(),
            "component_labels must hold both labels on PostgreSQL after add",
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
