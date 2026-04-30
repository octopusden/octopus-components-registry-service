package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.AuditLogEntity
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

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
        // Proof-of-exclusion: seed both an api row (via the live CRUD path) AND a synthetic
        // git-history row (direct repo write — same path /admin/migrate-history takes), then
        // assert the source=api filter returns only the api row. If the source predicate were
        // accidentally removed, this test would fail because the git-history row would leak
        // through. The earlier shape of this test only checked "all rows are source=api"
        // which silently passes when the fixture has no git-history rows.
        val name = uniqueName("sys036_src_api")
        val apiId = createComponent(name) // source=api row
        val historyEntityId = "sys036_history_${UUID.randomUUID().toString().take(8)}"
        seedSyntheticAuditRow(entityId = historyEntityId, source = "git-history")

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("source", "api")
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val sources = json["content"].map { it["source"].asText() }.toSet()
        assert(sources.all { it == "api" }) { "expected only api, got $sources" }

        val entityIds = json["content"].map { it["entityId"].asText() }.toSet()
        assert(entityIds.contains(apiId)) { "expected the api-sourced row $apiId in the response" }
        assert(!entityIds.contains(historyEntityId)) {
            "expected git-history row $historyEntityId to be excluded by source=api"
        }
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by action returns only matching rows")
    fun auditRecent_byAction_returnsMatching() {
        // Generate two rows for the same component: a CREATE and a DELETE. Filtering by
        // ?action=DELETE should return only the delete row.
        val name = uniqueName("sys036_act")
        val id = createComponent(name)
        deleteComponent(id)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("action", "DELETE")
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val actions = json["content"].map { it["action"].asText() }.toSet()
        assert(actions.all { it == "DELETE" }) { "expected only DELETE, got $actions" }
        assert(json["content"].any { it["entityId"].asText() == id }) {
            "expected to find the DELETE row for $id"
        }
        // Spot-check that the CREATE row from the same component IS reachable on a separate
        // call — proves we're filtering by action, not just always returning the same set.
        val createBody =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("action", "CREATE")
                        .param("entityId", id)
                        .param("size", "10"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val createActions = objectMapper.readTree(createBody)["content"].map { it["action"].asText() }.toSet()
        assert(createActions == setOf("CREATE")) { "expected only CREATE for $id, got $createActions" }
    }

    @Test
    @DisplayName("SYS-036 audit/recent filtered by half-open [from, to) date window")
    fun auditRecent_byDateWindow_halfOpen() {
        // Seed three synthetic rows at controlled timestamps so we can prove half-open
        // semantics deterministically. Live audit rows always use Instant.now() and we
        // can't pin those to specific instants without sleeping the test, hence direct repo
        // writes here. Window: [t1, t2). t0 < t1 < t2 < t3, so:
        //   - row at t0 → before window, excluded
        //   - row at t1 → exactly the lower bound, INCLUDED (closed end)
        //   - row at t2 → exactly the upper bound, EXCLUDED (open end)
        //   - row at t3 → after window, excluded
        val anchor = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val t0 = anchor
        val t1 = anchor.plus(1, ChronoUnit.HOURS)
        val t2 = anchor.plus(2, ChronoUnit.HOURS)
        val t3 = anchor.plus(3, ChronoUnit.HOURS)
        val tag = "sys036_window_${UUID.randomUUID().toString().take(8)}"
        val idT0 = "${tag}_t0"
        val idT1 = "${tag}_t1"
        val idT2 = "${tag}_t2"
        val idT3 = "${tag}_t3"
        seedSyntheticAuditRow(entityId = idT0, changedAt = t0)
        seedSyntheticAuditRow(entityId = idT1, changedAt = t1)
        seedSyntheticAuditRow(entityId = idT2, changedAt = t2)
        seedSyntheticAuditRow(entityId = idT3, changedAt = t3)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("from", t1.toString())
                        .param("to", t2.toString())
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)
        val ids = json["content"].map { it["entityId"].asText() }.filter { it.startsWith(tag) }.toSet()

        assert(ids == setOf(idT1)) {
            "expected only [t1] in [from=$t1, to=$t2), got $ids " +
                "(t0=$idT0 should be excluded as before-window, " +
                "t2=$idT2 should be excluded as upper-bound-exclusive, " +
                "t3=$idT3 should be excluded as after-window)"
        }
    }

    @Test
    @DisplayName("SYS-036 audit/recent default sort is changedAt DESC when caller doesn't supply sort=")
    fun auditRecent_defaultSort_changedAtDesc() {
        // Seed three rows with controlled, distinct timestamps. With no sort= parameter the
        // service default-sorts changedAt DESC (AuditServiceImpl.withDefaultSort), so we
        // expect the response to list our seeded rows in newest-first order.
        val anchor = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val tOldest = anchor
        val tMiddle = anchor.plus(1, ChronoUnit.HOURS)
        val tNewest = anchor.plus(2, ChronoUnit.HOURS)
        val tag = "sys036_sort_${UUID.randomUUID().toString().take(8)}"
        val idOldest = "${tag}_oldest"
        val idMiddle = "${tag}_middle"
        val idNewest = "${tag}_newest"
        // Insert intentionally out of order to make sure ordering comes from the query, not insert order.
        seedSyntheticAuditRow(entityId = idMiddle, changedAt = tMiddle)
        seedSyntheticAuditRow(entityId = idOldest, changedAt = tOldest)
        seedSyntheticAuditRow(entityId = idNewest, changedAt = tNewest)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("from", tOldest.toString())
                        .param("to", tNewest.plus(1, ChronoUnit.HOURS).toString())
                        .param("size", "500"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)
        val orderedIdsForTag =
            json["content"]
                .map { it["entityId"].asText() }
                .filter { it.startsWith(tag) }

        assert(orderedIdsForTag == listOf(idNewest, idMiddle, idOldest)) {
            "expected DESC order [newest, middle, oldest] for tag $tag, got $orderedIdsForTag"
        }
    }

    @Test
    @DisplayName("SYS-036 audit/recent without filters returns 200 (filters are optional)")
    fun auditRecent_noFilters_ok() {
        mvc
            .perform(get("/rest/api/4/audit/recent").with(adminJwt()))
            .andExpect(status().isOk)
    }

    /**
     * Direct repo write — bypasses the controller path so we can pin source/changedAt to
     * specific values for tests that need to prove exclusion or ordering. The live CRUD path
     * always writes source=api and changedAt=Instant.now(), which is fine for the happy-path
     * tests but not enough to prove the filter predicates are actually applied.
     */
    private fun seedSyntheticAuditRow(
        entityType: String = "Component",
        entityId: String,
        action: String = "CREATE",
        changedBy: String = "system",
        changedAt: Instant = Instant.now(),
        source: String = "api",
    ): AuditLogEntity =
        auditLogRepository.save(
            AuditLogEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                changedBy = changedBy,
                changedAt = changedAt,
                source = source,
            ),
        )
}
