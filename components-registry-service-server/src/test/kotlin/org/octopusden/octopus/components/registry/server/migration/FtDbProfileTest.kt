package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.hamcrest.Matchers.greaterThan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.MigrationStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * Verifies that the ft-db profile (H2 in-memory + auto-migrate) works correctly.
 * This profile is used by downstream FT environments (DMS, ORMS, releng).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@Timeout(120)
class FtDbProfileTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(FtDbProfileTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    fun `all components are in DB after startup with ft-db profile`() {
        val body =
            mvc
                .perform(get("/rest/api/4/admin/migration-status"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val status: MigrationStatus = objectMapper.readValue(body)

        assertTrue(status.db > 0, "Components should be in DB")
        assertEquals(0, status.git, "No components should remain in Git")
        assertEquals(status.total, status.db)
    }

    @Test
    fun `v3 API returns components from DB after ft-db auto-migrate`() {
        mvc
            .perform(get("/rest/api/3/components"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(greaterThan(0)))
    }

    @Test
    fun `v1 API returns components from DB after ft-db auto-migrate`() {
        mvc
            .perform(get("/rest/api/1/components"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.components").isArray)
            .andExpect(jsonPath("$.components.length()").value(greaterThan(0)))
    }
}
