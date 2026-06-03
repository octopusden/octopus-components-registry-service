package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * MIG-040 V4 contract tests for null scalar override rows.
 *
 * Covers the four V4 paths described in the F+G boundary doc:
 *   1. POST `/field-overrides` with `value: null` → 400 "use DELETE" (regression guard).
 *   2. PUT (PATCH) with `value: null` → 200 no-op (PATCH semantic; row unchanged).
 *   3. Non-null scalar override value created via POST round-trips through GET — surrogate
 *      for the import-created null-column row shape (entity injection out of scope here).
 *   4. DELETE on the override row → row gone; subsequent GET no longer includes it.
 *
 * The test seeds its own component via the V4 POST endpoint so it is self-contained
 * and does not depend on the auto-migrate fixture data.
 *
 * Note: the V4 POST endpoint does not accept null values (contract: use DELETE to remove
 * an override). Null-column rows can only be created by the import pipeline. Null-column
 * rows require entity injection to seed, which is out of scope here — those resolver-merge
 * semantics are covered by [ScalarNullOverrideRoundTripTest].
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class V4NullScalarOverrideContractTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(V4NullScalarOverrideContractTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun createComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name": "$name",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration": {"build": {"buildSystem": "MAVEN"}}}""",
                        ),
                )
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun createFieldOverride(
        componentId: String,
        payload: String,
    ) = mvc.perform(
        post("/rest/api/4/components/$componentId/field-overrides")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload),
    )

    private fun updateFieldOverride(
        componentId: String,
        overrideId: String,
        payload: String,
    ) = mvc.perform(
        patch("/rest/api/4/components/$componentId/field-overrides/$overrideId")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload),
    )

    private fun listFieldOverrides(componentId: String): List<com.fasterxml.jackson.databind.JsonNode> {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$componentId/field-overrides").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val arr = objectMapper.readTree(body)
        assertTrue(arr.isArray)
        return arr.toList()
    }

    // ========================================================================
    // Test 1: POST with value:null → 400 "use DELETE" (regression guard)
    // ========================================================================

    @Test
    @DisplayName("MIG-040-V4-1: POST field-override with value:null → 400 (use DELETE)")
    fun `MIG-040-V4-1 POST field-override with null value is rejected with 400`() {
        val compId = createComponent("null-override-contract-v4-1-${UUID.randomUUID()}")

        val payload =
            """
            {
              "overriddenAttribute": "build.buildFilePath",
              "versionRange": "[1.0,2.0)",
              "value": null
            }
            """.trimIndent()

        createFieldOverride(compId, payload).andExpect(status().isBadRequest)
    }

    // ========================================================================
    // Test 2: PUT (PATCH) with value:null → 200 no-op (PATCH semantic)
    // ========================================================================

    @Test
    @DisplayName("MIG-040-V4-2: PATCH field-override with value:null is a no-op (PATCH semantic)")
    fun `MIG-040-V4-2 PATCH field-override with null value is no-op - row unchanged`() {
        val compId = createComponent("null-override-contract-v4-2-${UUID.randomUUID()}")

        // Create a field override with a real value
        val createPayload =
            """
            {
              "overriddenAttribute": "build.buildFilePath",
              "versionRange": "[1.0,2.0)",
              "value": "SomeFile"
            }
            """.trimIndent()

        val createBody =
            createFieldOverride(compId, createPayload)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val overrideId = objectMapper.readTree(createBody)["id"].asText()

        // PATCH with value:null → should be 200 no-op
        val updatePayload = """{"value": null}"""
        updateFieldOverride(compId, overrideId, updatePayload)
            .andExpect(status().isOk)

        // Verify the row still has the original value (no-op PATCH semantic)
        val overrides = listFieldOverrides(compId)
        val updatedOverride = overrides.firstOrNull { it["id"].asText() == overrideId }
        assertTrue(
            updatedOverride != null,
            "Override row must still exist after null-value PATCH",
        )
        assertEquals(
            "SomeFile",
            updatedOverride!!["value"].asText(),
            "Override value must be unchanged after null-value PATCH (no-op PATCH semantic)",
        )
    }

    // ========================================================================
    // Test 3: Non-null field override round-trips through V4 GET
    // ========================================================================

    @Test
    @DisplayName("MIG-040-V4-3: non-null scalar override value round-trips through V4 list endpoint")
    fun `MIG-040-V4-3 non-null scalar override value round-trips through V4 GET field-overrides`() {
        val compId = createComponent("null-override-contract-v4-3-${UUID.randomUUID()}")

        val createPayload =
            """
            {
              "overriddenAttribute": "build.buildFilePath",
              "versionRange": "[5.0,6.0)",
              "value": "SpecialFile"
            }
            """.trimIndent()

        val createBody =
            createFieldOverride(compId, createPayload)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val overrideId = objectMapper.readTree(createBody)["id"].asText()

        val overrides = listFieldOverrides(compId)
        val override = overrides.firstOrNull { it["id"].asText() == overrideId }
        assertTrue(override != null, "Created override must appear in GET field-overrides")
        assertEquals("SpecialFile", override!!["value"].asText())
        assertEquals("SCALAR_OVERRIDE", override["rowType"].asText())
        assertEquals("build.buildFilePath", override["overriddenAttribute"].asText())
    }

    // ========================================================================
    // Test 4: DELETE removes the override row
    // ========================================================================

    @Test
    @DisplayName("MIG-040-V4-4: DELETE field-override removes the row")
    fun `MIG-040-V4-4 DELETE field-override removes the row`() {
        val compId = createComponent("null-override-contract-v4-4-${UUID.randomUUID()}")

        val createPayload =
            """
            {
              "overriddenAttribute": "build.buildFilePath",
              "versionRange": "[7.0,8.0)",
              "value": "ToDelete"
            }
            """.trimIndent()

        val createBody =
            createFieldOverride(compId, createPayload)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val overrideId = objectMapper.readTree(createBody)["id"].asText()

        // DELETE the override
        mvc.perform(
            delete("/rest/api/4/components/$compId/field-overrides/$overrideId").with(adminJwt()),
        ).andExpect(status().is2xxSuccessful)

        // Verify the row is gone
        val remainingOverrides = listFieldOverrides(compId)
        val deletedOverride = remainingOverrides.firstOrNull { it["id"].asText() == overrideId }
        assertTrue(
            deletedOverride == null,
            "Deleted override must no longer appear in GET field-overrides",
        )
    }
}
