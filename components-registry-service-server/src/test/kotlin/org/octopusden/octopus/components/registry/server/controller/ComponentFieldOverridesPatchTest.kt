package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Paths
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Item D — field overrides can ride the component PATCH as a desired-FULL-SET in
 * `fieldOverrides`, so the portal's one combined Save persists overrides + the
 * rest of the component atomically. Semantics:
 *   - absent/null      → overrides untouched
 *   - present array    → upsert by id, CREATE entries without id, DELETE any
 *                        existing (V4-editable) override not in the list
 * Applied in the same transaction as the component update; import-managed
 * markers (group-artifact-pattern) are out of scope and preserved.
 *
 * Integration test (ft-db = H2 + auto-migrate): drives the real V4 PATCH and
 * reads overrides back through the field-override list API.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class ComponentFieldOverridesPatchTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentFieldOverridesPatchTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("PATCH fieldOverrides=[create] adds a new override in the same request")
    fun `patch fieldOverrides creates`() {
        val id = newComponent()
        patchComponent(
            id,
            """"fieldOverrides":[{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"FileA"}]""",
        )
        val overrides = listFieldOverrides(id)
        assertEquals(1, overrides.size, "PATCH fieldOverrides must create the override")
        assertEquals("build.buildFilePath", overrides[0]["overriddenAttribute"].asText())
        assertEquals("FileA", overrides[0]["value"].asText())
    }

    @Test
    @DisplayName("PATCH fieldOverrides absent leaves existing overrides untouched")
    fun `patch without fieldOverrides is a no-op for overrides`() {
        val id = newComponent()
        seedOverride(id, "build.buildFilePath", "[1.0,2.0)", "FileA")
        // A component-only PATCH (no fieldOverrides key) must not wipe overrides.
        patchComponent(id, """"displayName":"Renamed"""")
        assertEquals(1, listFieldOverrides(id).size, "overrides must survive a fieldOverrides-absent PATCH")
    }

    @Test
    @DisplayName("PATCH fieldOverrides=[] (empty desired set) deletes all editable overrides")
    fun `patch empty fieldOverrides clears`() {
        val id = newComponent()
        seedOverride(id, "build.buildFilePath", "[1.0,2.0)", "FileA")
        patchComponent(id, """"fieldOverrides":[]""")
        assertEquals(0, listFieldOverrides(id).size, "empty desired set must delete all editable overrides")
    }

    @Test
    @DisplayName("PATCH fieldOverrides upserts by id (value change) and deletes the omitted one")
    fun `patch upsert and delete-omitted`() {
        val id = newComponent()
        val keepId = seedOverride(id, "build.buildFilePath", "[1.0,2.0)", "FileA")
        seedOverride(id, "build.javaVersion", "[1.0,2.0)", "17") // omitted below → must be deleted

        patchComponent(
            id,
            """"fieldOverrides":[{"id":"$keepId","overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"FileB"}]""",
        )

        val overrides = listFieldOverrides(id)
        assertEquals(1, overrides.size, "only the kept override should remain")
        assertEquals(keepId, overrides[0]["id"].asText())
        assertEquals("FileB", overrides[0]["value"].asText(), "the kept override must reflect the new value")
    }

    @Test
    @DisplayName("PATCH applies a component field AND fieldOverrides in one request, bumping version once")
    fun `patch component field and overrides together`() {
        val id = newComponent()
        val before = currentVersion(id)
        patchComponent(
            id,
            """"displayName":"Combined","fieldOverrides":[{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"FileA"}]""",
        )
        val detail = getComponent(id)
        assertEquals("Combined", detail["displayName"].asText())
        assertEquals(1, listFieldOverrides(id).size)
        assertEquals(before + 1, detail["version"].asLong(), "one combined PATCH must bump the version exactly once")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun newComponent(): String {
        val name = "fo-patch-${UUID.randomUUID().toString().take(8)}"
        val body = mvc.perform(
            post("/rest/api/4/components").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(
                """{"name":"$name","componentOwner":"owner1",""" +
                    """"group":{"groupKey":"org.example.test","isFake":false},""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            ),
        ).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun seedOverride(componentId: String, attribute: String, range: String, value: String): String {
        val body = mvc.perform(
            post("/rest/api/4/components/$componentId/field-overrides").with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"overriddenAttribute":"$attribute","versionRange":"$range","value":"$value"}"""),
        ).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun currentVersion(componentId: String): Long = getComponent(componentId)["version"].asLong()

    private fun getComponent(componentId: String): JsonNode {
        val body = mvc.perform(get("/rest/api/4/components/$componentId").with(adminJwt()))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun patchComponent(componentId: String, fieldsWithoutVersion: String) {
        val payload = """{"version":${currentVersion(componentId)},$fieldsWithoutVersion}"""
        mvc.perform(
            patch("/rest/api/4/components/$componentId").with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON).content(payload),
        ).andExpect(status().isOk)
    }

    private fun listFieldOverrides(componentId: String): List<JsonNode> {
        val body = mvc.perform(get("/rest/api/4/components/$componentId/field-overrides").with(adminJwt()))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(body).toList().also { assertTrue(it.isEmpty() || it[0].has("id")) }
    }
}
