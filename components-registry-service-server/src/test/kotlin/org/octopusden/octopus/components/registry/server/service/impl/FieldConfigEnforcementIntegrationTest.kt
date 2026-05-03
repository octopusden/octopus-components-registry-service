package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * Risk-1 mitigation — end-to-end test for [FieldConfigService].
 *
 * The unit suite (`FieldConfigServiceTest`) covers the visibility resolver
 * in isolation against a Mockito stub. This test closes the Spring-DI /
 * JPA / transaction loop:
 *
 *   1. Pick an existing component from the ft-db seed.
 *   2. As admin, write a field-config row that sets
 *      `component.displayName.visibility = "hidden"`.
 *   3. As editor, PATCH the component with a new `displayName`.
 *   4. Assert the stored `displayName` is unchanged — the value was
 *      silently stripped at the service layer per the Risk-1 contract.
 *
 * `@DirtiesContext` ensures the realm-scoped `field-config` row written
 * here doesn't leak into other tests in the same suite.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class FieldConfigEnforcementIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths
                .get(FieldConfigEnforcementIntegrationTest::class.java.getResource("/expected-data")!!.toURI())
                .parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun firstComponent(): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(editorJwt()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val root = objectMapper.readTree(body)
        val content = root.path("content")
        assertTrue(content.isArray && content.size() > 0)
        return content[0]
    }

    private fun getComponent(id: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    @Test
    @DisplayName("hidden displayName via field-config → PATCH with new displayName silently stripped")
    fun hiddenDisplayName_isStripped() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val originalDetail = getComponent(id)
        val originalDisplayName = originalDetail["displayName"].asText("")
        val version = originalDetail["version"].asLong()

        // Step 1: as admin, configure displayName=hidden via field-config.
        val fieldConfigPayload =
            mapOf(
                "component" to
                    mapOf(
                        "displayName" to mapOf("visibility" to "hidden"),
                    ),
            )
        mvc
            .perform(
                put("/rest/api/4/admin/config/field-config")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fieldConfigPayload)),
            ).andExpect(status().is2xxSuccessful)

        // Step 2: as editor, PATCH with a brand-new displayName.
        val attempted = "ATTEMPTED-CHANGE-${System.nanoTime()}"
        assertNotEquals(originalDisplayName, attempted, "test data invariant")
        val patchPayload =
            mapOf(
                "version" to version,
                "displayName" to attempted,
            )
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(patchPayload)),
            ).andExpect(status().is2xxSuccessful)

        // Step 3: assert the displayName did NOT change. The hidden gate
        // silently stripped the value before mutation.
        val updated = getComponent(id)
        assertEquals(
            originalDisplayName,
            updated["displayName"].asText(""),
            "displayName should be unchanged: hidden field write was silently stripped",
        )
    }

    @Test
    @DisplayName("editable displayName (explicit field-config) → PATCH applies normally")
    fun editableDisplayName_appliesNormally() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val originalDetail = getComponent(id)
        val version = originalDetail["version"].asLong()

        // Make the case order-independent: write an explicit
        // `displayName.visibility = "editable"` instead of relying on the
        // field-config row being absent. JUnit doesn't guarantee method
        // order, and the `hiddenDisplayName_isStripped` case writes a
        // persistent `field-config` row whose `hidden` value would mask
        // this test's contract if it ran first.
        val fieldConfigPayload =
            mapOf(
                "component" to
                    mapOf(
                        "displayName" to mapOf("visibility" to "editable"),
                    ),
            )
        mvc
            .perform(
                put("/rest/api/4/admin/config/field-config")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fieldConfigPayload)),
            ).andExpect(status().is2xxSuccessful)

        val attempted = "EDITABLE-CHANGE-${System.nanoTime()}"
        val patchPayload =
            mapOf(
                "version" to version,
                "displayName" to attempted,
            )
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(patchPayload)),
            ).andExpect(status().is2xxSuccessful)

        val updated = getComponent(id)
        assertEquals(
            attempted,
            updated["displayName"].asText(""),
            "displayName should reflect the patch when field-config marks it editable",
        )
    }
}
