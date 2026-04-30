package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-036 — `GET /rest/api/4/audit/recent` accepts optional filter query params so the
 * Portal `AuditLogPage` can drive its filter sidebar against the server. Without these
 * params the Portal would have to download every audit page and filter client-side,
 * which doesn't scale.
 *
 * Supported filters (each independently optional, ANDed when combined):
 *   - `entityType`        — currently only `Component` (capitalized; the filter is
 *                           a case-sensitive `cb.equal`). `FieldOverride` and other
 *                           entity types are reserved for future audit instrumentation.
 *   - `entityId`          — UUID of a specific entity (for entity-scoped history; identical
 *                           shape to `GET /audit/{entityType}/{entityId}` but reachable on
 *                           the same query as user/source/etc. filters)
 *   - `changedBy`         — username from `audit_log.changed_by`
 *   - `source`            — currently only `api` and `git-history`; other values are
 *                           reserved for future writers
 *   - `action`            — `CREATE` | `UPDATE` | `DELETE` | `RENAME` | `ARCHIVE` | …
 *   - `from`, `to`        — ISO-8601 instants; either or both, half-open `[from, to)`
 *
 * Test layer: integration. The `ft-db` profile gives us H2 + auto-migrate; each test
 * exercises the live `AuditService.getRecentChanges` filters against rows produced by
 * real component CRUD calls, so the wire shape stays in sync with the implementation.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class AuditLogFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(AuditLogFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","displayName":"$name"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun deleteComponent(id: String) {
        mvc
            .perform(delete("/rest/api/4/components/$id").with(adminJwt()))
            .andExpect(status().isNoContent)
    }

    private fun patchDisplayName(
        id: String,
        version: Long,
        displayName: String,
    ) {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"displayName":"$displayName"}"""),
            ).andExpect(status().isOk)
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by entityType returns only matching rows")
    fun auditRecent_byEntityType_returnsMatching() {
        val name = uniqueName("sys036_et")
        val id = createComponent(name)
        deleteComponent(id) // generates a Component-scoped audit row even if other types exist

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("entityType", "Component")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val entityTypes = json["content"].map { it["entityType"].asText() }.toSet()
        assert(entityTypes.all { it == "Component" }) { "expected only Component, got $entityTypes" }
        assert(json["content"].any { it["entityId"].asText() == id }) {
            "expected to find audit rows for component $id"
        }
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by entityType + entityId scopes to a single component")
    fun auditRecent_byEntityTypeAndId_singleComponent() {
        val name = uniqueName("sys036_eti")
        val id = createComponent(name)
        // Need version=0 from the create response. Re-fetch to read it cleanly.
        val detail =
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val version = objectMapper.readTree(detail)["version"].asLong()
        patchDisplayName(id, version, "${name}_renamed_display")

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("entityType", "Component")
                        .param("entityId", id)
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val ids = json["content"].map { it["entityId"].asText() }.toSet()
        assert(ids == setOf(id)) { "expected only $id, got $ids" }
        assert(json["content"].size() >= 2) {
            "expected ≥ 2 audit rows for $id (CREATE + UPDATE), got ${json["content"].size()}"
        }
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by changedBy returns only that user's rows")
    fun auditRecent_byChangedBy_returnsMatching() {
        // This test depends on CurrentUserResolver wiring (TDD §6.4): every AuditEvent
        // published by ComponentManagementServiceImpl carries `changedBy =
        // currentUserResolver.currentUsername()`. Without that wiring, runtime API rows
        // would write `changed_by = null` and this filter would always return empty.
        // If a future revert of CurrentUserResolver lands without bisecting this test,
        // the assertion will fail loudly here rather than silently pass on null rows.
        val name = uniqueName("sys036_user")
        createComponent(name) // adminJwt → preferred_username "alice"

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("changedBy", "alice")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val users = json["content"].map { it["changedBy"]?.asText() }.toSet()
        assert(users.all { it == "alice" }) { "expected only alice, got $users" }
        assert(users.contains("alice")) { "expected at least one alice row" }
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by unknown user returns empty page")
    fun auditRecent_byUnknownUser_empty() {
        mvc
            .perform(
                get("/rest/api/4/audit/recent")
                    .with(adminJwt())
                    .param("changedBy", "definitelynotauser_${UUID.randomUUID()}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by source='api' excludes git-history rows")
    fun auditRecent_bySource_excludesOthers() {
        val name = uniqueName("sys036_src")
        createComponent(name) // produces source=api rows

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("source", "api")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val sources = json["content"].map { it["source"].asText() }.toSet()
        assert(sources.all { it == "api" }) { "expected only api, got $sources" }
    }

    @Test
    @DisplayName("SYS-036 audit/recent without filters returns 200 (filters are optional)")
    fun auditRecent_noFilters_ok() {
        mvc
            .perform(get("/rest/api/4/audit/recent").with(adminJwt()))
            .andExpect(status().isOk)
    }
}
