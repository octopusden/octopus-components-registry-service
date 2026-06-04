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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * SYS-050 — creating, updating and deleting a field override (version-ranged
 * attribute override) is a real change to the component and must leave an audit
 * trail, just like editing a top-level attribute. Previously these write paths
 * bumped the parent version but published no AuditEvent, so version-range edits
 * were invisible in the component history.
 *
 * Integration test (ft-db = H2 + auto-migrate): drives the real V4 write paths
 * end-to-end and reads the resulting audit rows back through the audit API.
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
class FieldOverrideAuditTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(FieldOverrideAuditTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-050: create/update/delete field-override each writes a Component UPDATE audit row")
    fun `SYS-050 field-override writes are audited as Component UPDATE`() {
        val id = createComponent("sys050_${UUID.randomUUID().toString().take(8)}")

        // Baseline: component create wrote a CREATE row, no override UPDATEs yet.
        assertEquals(0, updateRowCount(id), "expected no UPDATE rows before any override edit, got ${historyActions(id)}")

        // CREATE override → one UPDATE audit row carrying the attribute in its diff.
        val overrideId =
            createFieldOverride(
                id,
                """{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"FileA"}""",
            )
        assertEquals(1, updateRowCount(id), "createFieldOverride must write 1 UPDATE row, got ${historyActions(id)}")
        assertTrue(
            anyUpdateMentions(id, "build.buildFilePath"),
            "override audit diff must mention the overridden attribute",
        )

        // UPDATE override (value change) → second UPDATE audit row.
        patchFieldOverride(id, overrideId, """{"value":"FileB"}""")
        assertEquals(2, updateRowCount(id), "updateFieldOverride must write a 2nd UPDATE row, got ${historyActions(id)}")

        // DELETE override → third UPDATE audit row.
        deleteFieldOverride(id, overrideId)
        assertEquals(3, updateRowCount(id), "deleteFieldOverride must write a 3rd UPDATE row, got ${historyActions(id)}")
    }

    @Test
    @DisplayName("SYS-050: a no-op field-override PATCH writes no audit row (SYS-048 guard holds)")
    fun `SYS-050 no-op override PATCH writes no audit row`() {
        val id = createComponent("sys050noop_${UUID.randomUUID().toString().take(8)}")
        val overrideId =
            createFieldOverride(
                id,
                """{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"Same"}""",
            )
        val afterCreate = updateRowCount(id)

        // PATCH with the same value — nothing actually changes.
        patchFieldOverride(id, overrideId, """{"value":"Same"}""")

        assertEquals(
            afterCreate,
            updateRowCount(id),
            "a no-op override PATCH must not add an audit row, got ${historyActions(id)}",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name",""" +
                                """"componentOwner":"owner1",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                        ),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun createFieldOverride(
        componentId: String,
        payload: String,
    ): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components/$componentId/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun patchFieldOverride(
        componentId: String,
        overrideId: String,
        payload: String,
    ) {
        mvc
            .perform(
                patch("/rest/api/4/components/$componentId/field-overrides/$overrideId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isOk)
    }

    private fun deleteFieldOverride(
        componentId: String,
        overrideId: String,
    ) {
        mvc
            .perform(delete("/rest/api/4/components/$componentId/field-overrides/$overrideId").with(adminJwt()))
            .andExpect(status().is2xxSuccessful)
    }

    private fun history(componentId: String): List<JsonNode> {
        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/Component/$componentId")
                        .with(adminJwt())
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["content"].toList()
    }

    private fun historyActions(componentId: String): List<String> = history(componentId).map { it["action"].asText() }

    private fun updateRowCount(componentId: String): Int = historyActions(componentId).count { it == "UPDATE" }

    // True iff some UPDATE entry's structured changeDiff has a key naming the
    // attribute (field-override diffs are keyed `fieldOverride[<attr>]`). Asserts
    // on the structured diff rather than a brittle whole-node toString match.
    private fun anyUpdateMentions(
        componentId: String,
        attribute: String,
    ): Boolean =
        history(componentId)
            .filter { it["action"].asText() == "UPDATE" }
            .any { entry ->
                val diff = entry.path("changeDiff")
                diff.isObject && diff.fieldNames().asSequence().any { it.contains(attribute) }
            }
}
