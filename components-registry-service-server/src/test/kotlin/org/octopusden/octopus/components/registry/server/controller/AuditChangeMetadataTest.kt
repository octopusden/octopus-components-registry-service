package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.AuditLogRepository
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
import java.nio.file.Paths
import java.util.UUID

/**
 * Change metadata on save (Jira task key + free-text comment). The Portal prompts
 * for an optional Jira task key and a comment when saving a component change, and
 * CRS persists them on the corresponding `audit_log` row (alongside correlationId),
 * returns them in `AuditLogResponse`, and supports filtering `audit/recent` by key.
 *
 * Both fields are optional. A blank/whitespace key is a valid "no key" (stored
 * null, never 400); a non-blank key must match a Jira key pattern (`ABC-123`) or
 * the create/update is rejected with 400 by `@Valid`/`@field:Pattern`.
 *
 * Test layer: integration — exercises the full stack (controller validation →
 * service → AuditEventListener → entity → V3 migration → response + filter).
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
class AuditChangeMetadataTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    @Suppress("UnusedPrivateProperty")
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(AuditChangeMetadataTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    /** POST a component, optionally with change metadata. Returns the new id. Expects 201. */
    private fun createComponent(
        name: String,
        jiraTaskKey: String? = null,
        changeComment: String? = null,
    ): String {
        val extra = buildString {
            if (jiraTaskKey != null) append(""","jiraTaskKey":${objectMapper.writeValueAsString(jiraTaskKey)}""")
            if (changeComment != null) append(""","changeComment":${objectMapper.writeValueAsString(changeComment)}""")
        }
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","displayName":"$name",""" +
                                """"componentOwner":"owner1",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}$extra}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun versionOf(id: String): Long {
        val detail =
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(detail)["version"].asLong()
    }

    /** Fetch the single audit row for (entityId, action). Fails if not exactly one. */
    private fun auditRow(
        entityId: String,
        action: String,
    ): com.fasterxml.jackson.databind.JsonNode {
        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("entityId", entityId)
                        .param("action", action)
                        .param("size", "10"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val rows = objectMapper.readTree(body)["content"]
        assert(rows.size() == 1) { "expected exactly one $action row for $entityId, got ${rows.size()}" }
        return rows[0]
    }

    @Test
    @DisplayName("CREATE persists jiraTaskKey + changeComment and returns them on the audit row")
    fun create_persistsMetadata() {
        val key = "ABC-123"
        val comment = "initial import"
        val id = createComponent(uniqueName("meta_create"), jiraTaskKey = key, changeComment = comment)

        val row = auditRow(id, "CREATE")
        assert(row["jiraTaskKey"].asText() == key) { "expected jiraTaskKey=$key, got ${row["jiraTaskKey"]}" }
        assert(row["changeComment"].asText() == comment) {
            "expected changeComment=$comment, got ${row["changeComment"]}"
        }
    }

    @Test
    @DisplayName("UPDATE persists jiraTaskKey + changeComment on the UPDATE audit row")
    fun update_persistsMetadata() {
        val id = createComponent(uniqueName("meta_update"))
        val version = versionOf(id)
        val key = "DEF-456"
        val comment = "renamed display name per ticket"
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":$version,"displayName":"changed_display",""" +
                            """"jiraTaskKey":"$key","changeComment":"$comment"}""",
                    ),
            ).andExpect(status().isOk)

        val row = auditRow(id, "UPDATE")
        assert(row["jiraTaskKey"].asText() == key) { "expected jiraTaskKey=$key, got ${row["jiraTaskKey"]}" }
        assert(row["changeComment"].asText() == comment) {
            "expected changeComment=$comment, got ${row["changeComment"]}"
        }
    }

    @Test
    @DisplayName("Malformed jiraTaskKey is rejected with 400")
    fun malformedKey_rejected() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"${uniqueName("meta_bad")}","displayName":"x","componentOwner":"owner1",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}},""" +
                            """"jiraTaskKey":"not a key"}""",
                    ),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("Blank jiraTaskKey is accepted (no 400) and stored as null; comment still persists")
    fun blankKey_accepted_storedNull() {
        val comment = "no ticket for this one"
        val id = createComponent(uniqueName("meta_blank"), jiraTaskKey = "   ", changeComment = comment)

        val row = auditRow(id, "CREATE")
        assert(row["jiraTaskKey"].isNull) { "expected null jiraTaskKey for blank input, got ${row["jiraTaskKey"]}" }
        assert(row["changeComment"].asText() == comment) {
            "expected changeComment=$comment, got ${row["changeComment"]}"
        }
    }

    @Test
    @DisplayName("audit/recent filtered by jiraTaskKey returns only matching rows")
    fun filterByJiraTaskKey_returnsMatching() {
        val keyA = "PROJ-1001"
        val idA = createComponent(uniqueName("meta_fa"), jiraTaskKey = keyA, changeComment = "a")
        val idB = createComponent(uniqueName("meta_fb"), jiraTaskKey = "PROJ-2002", changeComment = "b")

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("jiraTaskKey", keyA)
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val keys = json["content"].map { it["jiraTaskKey"]?.asText() }.toSet()
        assert(keys.all { it == keyA }) { "expected only $keyA, got $keys" }
        val ids = json["content"].map { it["entityId"].asText() }.toSet()
        assert(ids.contains(idA)) { "expected the matching component $idA in the response" }
        assert(!ids.contains(idB)) { "expected non-matching component $idB to be excluded" }
    }
}
