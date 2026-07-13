package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.nio.file.Paths
import java.util.UUID

/**
 * A single save is one transaction that can change several entities — e.g. moving
 * the base Java version AND adding a per-range override in one PATCH writes two
 * `audit_log` rows (`build.javaVersion` + `fieldOverride[build.javaVersion]`).
 * Those rows must share one `correlation_id` so the audit history can group them
 * into a single transaction record; rows from a DIFFERENT save must get a
 * different id (no ThreadLocal leak across saves on a pooled request thread).
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
class AuditCorrelationIdTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(AuditCorrelationIdTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(name: String): String {
        // Unique group per component: the combined base/override PATCH re-runs the
        // cross-component ownership check (409), which a group shared across test
        // components would trip — unrelated to what we're asserting here.
        val groupKey = "org.example.${UUID.randomUUID().toString().take(8)}"
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","displayName":"$name","componentOwner":"owner1",""" +
                                """"group":{"groupKey":"$groupKey","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN","javaVersion":"1.8"}}}""",
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

    /** Correlation ids of the UPDATE rows for a component, newest first. */
    private fun updateCorrelationIds(id: String): List<String?> {
        val body =
            mvc
                .perform(
                    get("/rest/api/4/audit/recent")
                        .with(adminJwt())
                        .param("entityId", id)
                        .param("action", "UPDATE")
                        .param("size", "50"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["content"].map {
            it["correlationId"].let { c -> if (c == null || c.isNull) null else c.asText() }
        }
    }

    /** One PATCH that changes the base Java version AND adds a `(,2)` override — two
     *  audit rows in one transaction. */
    private fun combinedSave(
        id: String,
        base: String,
        overrideValue: String,
    ) {
        val res =
            mvc
                .perform(
                    patch("/rest/api/4/components/$id")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"version":${versionOf(id)},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN","javaVersion":"$base"}},""" +
                                """"fieldOverrides":[{"overriddenAttribute":"build.javaVersion",""" +
                                """"versionRange":"(,2)","value":"$overrideValue"}]}""",
                        ),
                ).andReturn().response
        assert(res.status == 200) { "combined save failed: HTTP ${res.status} — ${res.contentAsString}" }
    }

    /** A base-only PATCH (one UPDATE audit row) — a second, independent save. */
    private fun baseSave(
        id: String,
        java: String,
    ) {
        val res =
            mvc
                .perform(
                    patch("/rest/api/4/components/$id")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"version":${versionOf(id)},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN","javaVersion":"$java"}}}""",
                        ),
                ).andReturn().response
        assert(res.status == 200) { "base save failed: HTTP ${res.status} — ${res.contentAsString}" }
    }

    @Test
    @DisplayName("all audit rows written in one save share a single non-null correlationId")
    fun oneSave_sharesCorrelationId() {
        val id = createComponent(uniqueName("corr_same"))
        // Base 1.8 → 17 AND a new (,2) → 1.8 override, in one PATCH → two UPDATE rows.
        combinedSave(id, base = "17", overrideValue = "1.8")

        val ids = updateCorrelationIds(id)
        assert(ids.size >= 2) { "expected at least two UPDATE rows for the combined save, got ${ids.size}" }
        assert(ids.none { it == null }) { "every audit row must carry a correlationId, got $ids" }
        assert(ids.toSet().size == 1) { "all rows from one save must share one correlationId, got ${ids.toSet()}" }
    }

    @Test
    @DisplayName("audit rows from different saves get different correlationIds (no ThreadLocal leak)")
    fun differentSaves_differentCorrelationId() {
        val id = createComponent(uniqueName("corr_diff"))
        combinedSave(id, base = "17", overrideValue = "1.8")
        val first = updateCorrelationIds(id).toSet()
        assert(first.size == 1) { "first save should share one id, got $first" }
        assert(first.none { it == null }) { "first save's id must be non-null, got $first" }

        // A second, independent save (new request → new transaction → new id).
        baseSave(id, java = "21")
        val all = updateCorrelationIds(id).toSet()

        val fromSecond = all - first
        assert(fromSecond.size == 1) { "second save must add exactly one new correlationId, got all=$all first=$first" }
        assert(fromSecond.single() != null) { "second save's id must be non-null, got $fromSecond" }
    }
}
