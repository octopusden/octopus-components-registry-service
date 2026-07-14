package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-053 — a component PATCH that changes only section fields (base
 * configuration build/escrow/jira scalars, versionRange, or section child
 * collections such as vcsEntries) is a real change and must leave an audit
 * trail, exactly like editing a top-level attribute. Previously the audit
 * snapshots captured only top-level scalars, so a section-only save produced
 * identical old/new maps and the SYS-048 no-op guard silently dropped the
 * row — the value persisted but History stayed empty.
 *
 * Integration test (ft-db = H2 + auto-migrate): drives the real V4 PATCH
 * path end-to-end and reads the resulting audit rows back through the audit
 * API.
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
class ComponentSectionAuditTest {
    private val vcsPayload =
        """{"baseConfiguration":{"vcsEntries":[""" +
            """{"name":"main","vcsPath":"ssh://git@vcs.example.com/proj/repo.git","branch":"master"}]}}"""

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentSectionAuditTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-053: PATCH of build.mavenVersion writes an UPDATE audit row with a field-level diff")
    fun `SYS-053 build scalar PATCH writes an UPDATE audit row with field-level diff`() {
        val id = createComponent("sys053b_${UUID.randomUUID().toString().take(8)}", mavenVersion = "3.8")
        assertEquals(0, updateRowCount(id), "expected no UPDATE rows right after create, got ${historyActions(id)}")

        patchComponent(id, """{"baseConfiguration":{"build":{"mavenVersion":"3.9"}}}""")

        assertEquals(1, updateRowCount(id), "build-section PATCH must write 1 UPDATE row, got ${historyActions(id)}")
        val change = diffEntry(id, "build.mavenVersion") ?: error("diff must carry key build.mavenVersion: ${diffs(id)}")
        assertEquals("3.8", change.path("old").asText(), "diff.old must be the pre-PATCH value")
        assertEquals("3.9", change.path("new").asText(), "diff.new must be the patched value")
    }

    @Test
    @DisplayName("SYS-053: PATCH of jira.projectKey writes an UPDATE audit row")
    fun `SYS-053 jira scalar PATCH writes an UPDATE audit row`() {
        val id = createComponent("sys053j_${UUID.randomUUID().toString().take(8)}")

        patchComponent(id, """{"baseConfiguration":{"jira":{"projectKey":"PRJX"}}}""")

        assertEquals(1, updateRowCount(id), "jira-section PATCH must write 1 UPDATE row, got ${historyActions(id)}")
        assertTrue(diffEntry(id, "jira.projectKey") != null, "diff must carry key jira.projectKey: ${diffs(id)}")
    }

    @Test
    @DisplayName("SYS-053: PATCH of vcsEntries (section child collection) writes an UPDATE audit row")
    fun `SYS-053 vcsEntries PATCH writes an UPDATE audit row`() {
        val id = createComponent("sys053v_${UUID.randomUUID().toString().take(8)}")

        patchComponent(id, vcsPayload)

        assertEquals(1, updateRowCount(id), "vcsEntries PATCH must write 1 UPDATE row, got ${historyActions(id)}")
        assertTrue(diffEntry(id, "vcsEntries") != null, "diff must carry key vcsEntries: ${diffs(id)}")
    }

    @Test
    @DisplayName("SYS-053: PATCH of requiredTools (repo-synced junction collection) audits once; identical re-send is a no-op")
    fun `SYS-053 requiredTools PATCH writes an UPDATE audit row and identical re-send does not`() {
        val id = createComponent("sys053t_${UUID.randomUUID().toString().take(8)}")
        val payload = """{"baseConfiguration":{"requiredTools":["BuildEnv","Oracle"]}}"""

        patchComponent(id, payload)
        assertEquals(1, updateRowCount(id), "requiredTools PATCH must write 1 UPDATE row, got ${historyActions(id)}")
        assertTrue(diffEntry(id, "requiredTools") != null, "diff must carry key requiredTools: ${diffs(id)}")

        // The junction is synced via a repo-direct delete+insert — identical
        // content must still compare equal through the in-memory refresh.
        patchComponent(id, payload)
        assertEquals(1, updateRowCount(id), "identical requiredTools re-send must stay a no-op, got ${historyActions(id)}")
    }

    @Test
    @DisplayName("SYS-053: PATCH of securityGroups (content-sorted component collection) audits once; identical re-send is a no-op")
    fun `SYS-053 securityGroups PATCH writes an UPDATE audit row and identical re-send does not`() {
        val id = createComponent("sys053s_${UUID.randomUUID().toString().take(8)}")
        val payload = """{"securityGroups":[{"groupType":"read","groupName":"vcs-e2e-group"}]}"""

        patchComponent(id, payload)
        assertEquals(1, updateRowCount(id), "securityGroups PATCH must write 1 UPDATE row, got ${historyActions(id)}")
        assertTrue(diffEntry(id, "securityGroups") != null, "diff must carry key securityGroups: ${diffs(id)}")

        // securityGroups is the one snapshot sorted by content (no sortOrder
        // column) — an identical REPLACE must stay diff-equal.
        patchComponent(id, payload)
        assertEquals(1, updateRowCount(id), "identical securityGroups re-send must stay a no-op, got ${historyActions(id)}")
    }

    @Test
    @DisplayName("SYS-053: a no-op section PATCH (same scalar, identical collection) writes no audit row — SYS-048 holds")
    fun `SYS-053 no-op section PATCH writes no audit row`() {
        val id = createComponent("sys053n_${UUID.randomUUID().toString().take(8)}", mavenVersion = "3.9")
        patchComponent(id, vcsPayload)
        val afterRealChange = updateRowCount(id)

        // Same scalar value again — nothing actually changes.
        patchComponent(id, """{"baseConfiguration":{"build":{"mavenVersion":"3.9"}}}""")
        // Identical collection again — the REPLACE recreates child rows, but the
        // content is byte-identical, so no audit row may appear (row-id churn must
        // not leak into the snapshot).
        patchComponent(id, vcsPayload)

        assertEquals(
            afterRealChange,
            updateRowCount(id),
            "a no-op section PATCH must not add an audit row, got ${historyActions(id)}",
        )
    }

    // ── helpers ──

    private fun createComponent(
        name: String,
        mavenVersion: String? = null,
    ): String {
        val build =
            if (mavenVersion != null) {
                """{"buildSystem":"MAVEN","mavenVersion":"$mavenVersion"}"""
            } else {
                """{"buildSystem":"MAVEN"}"""
            }
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
                                """"baseConfiguration":{"build":$build}}""",
                        ),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun currentVersion(componentId: String): Long {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$componentId").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["version"].asLong()
    }

    private fun patchComponent(
        componentId: String,
        payloadWithoutVersion: String,
    ) {
        // Inject the current optimistic-lock version into the caller's payload.
        val payload = """{"version":${currentVersion(componentId)},${payloadWithoutVersion.removePrefix("{")}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$componentId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isOk)
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

    private fun diffs(componentId: String): List<JsonNode> =
        history(componentId)
            .filter { it["action"].asText() == "UPDATE" }
            .map { it.path("changeDiff") }

    // The `{ "old": ..., "new": ... }` node for `key` from the newest UPDATE
    // entry whose structured changeDiff carries that key, or null.
    private fun diffEntry(
        componentId: String,
        key: String,
    ): JsonNode? = diffs(componentId).firstOrNull { it.isObject && it.has(key) }?.get(key)
}
