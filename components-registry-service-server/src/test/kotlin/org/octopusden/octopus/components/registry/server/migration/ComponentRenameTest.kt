package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-028 — `PATCH /rest/api/4/components/{id}` with a `name` field renames the component,
 * per Octopus REST API Guidelines (PATCH = partial update of a resource field). Plus the
 * existing `GET /rest/api/4/components/{idOrName}` now accepts either a UUID or a name so
 * downstream callers that only know components by name can resolve the id before PATCH.
 * The polymorphic path matches peer services (DMS, ORMS, Releng, CRS v1-v3).
 *
 * Covers: happy path, blank name → 400, conflict 409, not-found 404, audit RENAME action
 * emitted, stale optimistic-lock version, and combined rename + field update in one PATCH.
 *
 * Each test creates its own throwaway component through the v4 create endpoint so they
 * are isolated from each other (H2 in-memory state persists across methods in the same
 * @SpringBootTest context).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ComponentRenameTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentRenameTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(name: String): JsonNode {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","displayName":"$name"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun fetchById(id: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun patch(
        id: String,
        body: String,
    ) = mvc.perform(
        patch("/rest/api/4/components/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    @Test
    @DisplayName("SYS-028: PATCH with new name renames the component")
    fun rename_happyPath() {
        val created = createComponent(uniqueName("SYS028_HAPPY"))
        val id = created.path("id").asText()
        val oldName = created.path("name").asText()
        val version = created.path("version").asLong()
        val newName = uniqueName("SYS028_HAPPY_NEW")

        patch(id, """{"version":$version,"name":"$newName"}""").andExpect(status().isOk)

        mvc.perform(get("/rest/api/4/components/$newName")).andExpect(status().isOk)
        mvc.perform(get("/rest/api/4/components/$oldName")).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("SYS-028: GET /{idOrName} returns 404 for a missing name")
    fun lookupByName_notFound() {
        mvc
            .perform(get("/rest/api/4/components/${uniqueName("SYS028_MISSING")}"))
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("SYS-028: GET /{idOrName} resolves a UUID-shaped name by name after UUID lookup misses")
    fun lookupByName_uuidShaped_fallsBackToNameLookup() {
        // Component whose `name` happens to parse as a UUID. The controller's
        // polymorphic dispatch parses the path as a UUID first; that UUID won't
        // match the entity's auto-generated id, so the fallback to
        // getComponentByName() is the only way this resolves.
        val uuidShapedName = UUID.randomUUID().toString()
        val created = createComponent(uuidShapedName)
        val id = created.path("id").asText()

        mvc
            .perform(get("/rest/api/4/components/$uuidShapedName"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.name").value(uuidShapedName))
    }

    @Test
    @DisplayName("SYS-028: PATCH with blank name returns 400")
    fun rename_blankName() {
        val created = createComponent(uniqueName("SYS028_BLANK"))
        val id = created.path("id").asText()
        val version = created.path("version").asLong()

        patch(id, """{"version":$version,"name":"   "}""").andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-028: PATCH name-to-existing returns 409")
    fun rename_conflict() {
        val firstCreated = createComponent(uniqueName("SYS028_SRC"))
        val secondCreated = createComponent(uniqueName("SYS028_DST"))

        patch(
            firstCreated.path("id").asText(),
            """{"version":${firstCreated.path("version").asLong()},"name":"${secondCreated.path("name").asText()}"}""",
        ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("SYS-028: PATCH on a non-existing id returns 404")
    fun rename_notFound() {
        patch(
            "00000000-0000-0000-0000-000000000000",
            """{"version":0,"name":"${uniqueName("SYS028_404")}"}""",
        ).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("SYS-028: rename emits an audit_log RENAME entry")
    fun rename_emitsAuditRename() {
        val created = createComponent(uniqueName("SYS028_AUDIT"))
        val id = created.path("id").asText()
        val version = created.path("version").asLong()
        val newName = uniqueName("SYS028_AUDIT_NEW")

        patch(id, """{"version":$version,"name":"$newName"}""").andExpect(status().isOk)

        val audit =
            mvc
                .perform(get("/rest/api/4/audit/Component/$id"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val entries = objectMapper.readTree(audit).path("content")
        val actions = (0 until entries.size()).map { entries.get(it).path("action").asText() }
        assertEquals(
            true,
            actions.contains("RENAME"),
            "Expected audit_log to contain a RENAME action for $id; got $actions",
        )
    }

    @Test
    @DisplayName("SYS-028: PATCH with stale version returns 409 (optimistic lock)")
    fun rename_staleVersion() {
        val created = createComponent(uniqueName("SYS028_STALE"))
        val id = created.path("id").asText()
        val staleVersion = created.path("version").asLong() + 100

        patch(id, """{"version":$staleVersion,"name":"${uniqueName("SYS028_STALE_NEW")}"}""")
            .andExpect(status().isConflict)
    }

    @Test
    @DisplayName("SYS-028: PATCH can rename and update another field in one call")
    fun rename_combinedWithFieldUpdate() {
        val created = createComponent(uniqueName("SYS028_COMBO"))
        val id = created.path("id").asText()
        val version = created.path("version").asLong()
        val newName = uniqueName("SYS028_COMBO_NEW")
        val newOwner = "sys028-owner@example.org"

        patch(
            id,
            """{"version":$version,"name":"$newName","componentOwner":"$newOwner"}""",
        ).andExpect(status().isOk)

        val after = fetchById(id)
        assertEquals(newName, after.path("name").asText())
        assertEquals(newOwner, after.path("componentOwner").asText())
    }
}
