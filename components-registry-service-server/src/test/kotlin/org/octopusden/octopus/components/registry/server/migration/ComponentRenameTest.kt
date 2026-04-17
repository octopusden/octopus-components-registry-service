package org.octopusden.octopus.components.registry.server.migration

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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-028 — `POST /rest/api/4/components/{name}/rename` renames a DB-sourced component.
 *
 * Motivation: under ft-db (and any DB-backed deployment) CRS does not re-read DSL after
 * startup. Downstream rename workflows that relied on editing the Groovy DSL and letting
 * CRS pick it up (legacy git-resolver flow) silently break. Rename must be a first-class
 * API operation on the DB.
 *
 * This test lands RED first — the endpoint is not yet implemented, so the POST returns
 * 404 or 405 — and turns GREEN when the endpoint + service cascade + audit entry are in
 * place (see requirement SYS-028 in docs/db-migration/requirements-common.md for
 * the full acceptance criteria).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
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

    private fun firstDbComponentName(): String {
        val body =
            mvc
                .perform(get("/rest/api/4/components").param("page", "0").param("size", "1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val node = objectMapper.readTree(body).path("content").get(0)
        return node.path("name").asText()
    }

    @Test
    @DisplayName("SYS-028: rename endpoint moves a DB component to a new name")
    fun rename_happyPath() {
        val oldName = firstDbComponentName()
        val newName = "${oldName}_RENAMED_SYS028"

        mvc
            .perform(
                post("/rest/api/4/components/$oldName/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newName":"$newName"}"""),
            ).andExpect(status().isOk)

        mvc.perform(get("/rest/api/4/components/$newName")).andExpect(status().isOk)
        mvc.perform(get("/rest/api/4/components/$oldName")).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("SYS-028: rename with blank newName returns 400")
    fun rename_blankNewName() {
        val oldName = firstDbComponentName()
        mvc
            .perform(
                post("/rest/api/4/components/$oldName/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newName":"   "}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-028: rename to an already-existing name returns 409")
    fun rename_conflict() {
        val body =
            mvc
                .perform(get("/rest/api/4/components").param("page", "0").param("size", "2"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val content = objectMapper.readTree(body).path("content")
        val first = content.get(0).path("name").asText()
        val second = content.get(1).path("name").asText()

        mvc
            .perform(
                post("/rest/api/4/components/$first/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newName":"$second"}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("SYS-028: rename of a non-existing component returns 404")
    fun rename_notFound() {
        mvc
            .perform(
                post("/rest/api/4/components/DOES_NOT_EXIST_SYS028/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newName":"ANY_NAME"}"""),
            ).andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("SYS-028: rename writes an audit_log RENAME entry")
    fun rename_emitsAudit() {
        val oldName = firstDbComponentName()
        val newName = "${oldName}_RENAMED_AUDIT"

        mvc
            .perform(
                post("/rest/api/4/components/$oldName/rename")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newName":"$newName"}"""),
            ).andExpect(status().isOk)

        val audit =
            mvc
                .perform(get("/rest/api/4/audit").param("componentName", newName))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val entries = objectMapper.readTree(audit).path("content")
        val actions = (0 until entries.size()).map { entries.get(it).path("action").asText() }
        assertEquals(
            true,
            actions.contains("RENAME"),
            "Expected audit_log to contain a RENAME action for $newName; got $actions",
        )
    }
}
