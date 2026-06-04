package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
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
import org.springframework.data.domain.PageRequest
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
 * End-to-end (HTTP → service → Hibernate → H2 → service → HTTP) coverage for the
 * v4 multi-value `releaseManager` / `securityChampion` write paths:
 *   - create canonicalization (trim → drop blank → keep-first dedupe),
 *   - PATCH replace-whole-list, explicit empty-list clear, null = no-touch,
 *   - reorder preserved,
 *   - PATCH audit composes the comma-joined value (scalarAuditMap join path).
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
class MultiValuePeopleV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    init {
        val testResourcesPath =
            Paths.get(MultiValuePeopleV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun create(
        name: String,
        releaseManager: List<String> = emptyList(),
        securityChampion: List<String> = emptyList(),
    ): JsonNode {
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    // componentOwner is a required (non-blank) v4 create field.
                    "componentOwner" to "owner1",
                    "group" to mapOf("groupKey" to "org.example.test", "isFake" to false),
                    "baseConfiguration" to mapOf("build" to mapOf("buildSystem" to "MAVEN")),
                    "releaseManager" to releaseManager,
                    "securityChampion" to securityChampion,
                ),
            )
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().is2xxSuccessful)
                .andReturn().response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun getComponent(id: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun patchComponent(
        id: String,
        version: Long,
        fields: Map<String, Any?>,
    ): JsonNode {
        val body = objectMapper.writeValueAsString(mapOf("version" to version) + fields)
        val response =
            mvc
                .perform(
                    patch("/rest/api/4/components/$id")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun JsonNode.stringList(field: String): List<String> = this[field].map { it.asText() }

    @Test
    @DisplayName("SYS-044: CREATE canonicalizes people: [\" alice \", \"\", \"alice\", \"bob\"] -> [\"alice\", \"bob\"]")
    fun `SYS-044 CREATE canonicalizes people`() {
        val created = create(
            uniqueName("rmsc_create"),
            releaseManager = listOf(" alice ", "", "alice", "bob"),
            securityChampion = listOf(" carol ", "carol", "dave"),
        )
        assertEquals(listOf("alice", "bob"), created.stringList("releaseManager"))
        assertEquals(listOf("carol", "dave"), created.stringList("securityChampion"))
    }

    @Test
    @DisplayName("SYS-044: PATCH replaces the whole ordered list")
    fun `SYS-044 PATCH replaces the whole ordered list`() {
        val created = create(uniqueName("rmsc_replace"), releaseManager = listOf("alice"))
        val id = created["id"].asText()
        patchComponent(id, created["version"].asLong(), mapOf("releaseManager" to listOf("x", "y")))
        assertEquals(listOf("x", "y"), getComponent(id).stringList("releaseManager"))
    }

    @Test
    @DisplayName("SYS-044: PATCH with empty list clears the ordered list")
    fun `SYS-044 PATCH with empty list clears the ordered list`() {
        val created = create(uniqueName("rmsc_clear"), releaseManager = listOf("alice", "bob"))
        val id = created["id"].asText()
        patchComponent(id, created["version"].asLong(), mapOf("releaseManager" to emptyList<String>()))
        assertEquals(emptyList<String>(), getComponent(id).stringList("releaseManager"))
    }

    @Test
    @DisplayName("SYS-044: PATCH without the field (null) does not touch the stored list")
    fun `SYS-044 PATCH null does not touch the stored list`() {
        val created = create(uniqueName("rmsc_notouch"), releaseManager = listOf("alice", "bob"))
        val id = created["id"].asText()
        // PATCH a different field; releaseManager absent => null => don't touch.
        patchComponent(id, created["version"].asLong(), mapOf("displayName" to "Renamed"))
        assertEquals(listOf("alice", "bob"), getComponent(id).stringList("releaseManager"))
    }

    @Test
    @DisplayName("SYS-044: PATCH canonicalizes people the same way as create")
    fun `SYS-044 PATCH canonicalizes people the same way as create`() {
        val created = create(uniqueName("rmsc_patchcanon"))
        val id = created["id"].asText()
        patchComponent(
            id,
            created["version"].asLong(),
            mapOf("releaseManager" to listOf(" alice ", "", "alice", "bob")),
        )
        assertEquals(listOf("alice", "bob"), getComponent(id).stringList("releaseManager"))
    }

    @Test
    @DisplayName("SYS-044: PATCH preserves reordering (order is meaningful)")
    fun `SYS-044 PATCH preserves reordering`() {
        val created = create(uniqueName("rmsc_reorder"), releaseManager = listOf("a", "b", "c"))
        val id = created["id"].asText()
        patchComponent(id, created["version"].asLong(), mapOf("releaseManager" to listOf("c", "a", "b")))
        assertEquals(listOf("c", "a", "b"), getComponent(id).stringList("releaseManager"))
    }

    @Test
    @DisplayName("SYS-044: PATCH audit composes the comma-joined value in scalarAuditMap")
    fun `SYS-044 PATCH audit composes the comma-joined value`() {
        val created = create(uniqueName("rmsc_audit"))
        val id = created["id"].asText()
        patchComponent(id, created["version"].asLong(), mapOf("releaseManager" to listOf("alice", "bob")))

        val updateRow =
            auditLogRepository
                .findByEntityTypeAndEntityId("Component", id, PageRequest.of(0, 50))
                .content
                // Scoped to this component's id already; pick the most recent UPDATE
                // so we can never latch onto an earlier/unrelated row.
                .sortedByDescending { it.changedAt }
                .first { it.action == "UPDATE" }
        assertEquals("alice,bob", updateRow.newValue?.get("releaseManager"))
    }
}
