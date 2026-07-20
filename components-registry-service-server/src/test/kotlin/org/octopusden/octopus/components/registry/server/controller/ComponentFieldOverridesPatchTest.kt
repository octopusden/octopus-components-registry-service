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
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
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

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @Autowired
    private lateinit var componentRepository: ComponentRepository

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
        assertEquals("[1.0,2.0)", overrides[0]["versionRange"].asText(), "the created range must be persisted")
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

    // ── SYS-065: echo-safe desired-set save (legacy composite ranges) ────────

    @Test
    @DisplayName(
        "SYS-065: desired-set PATCH echoing an unchanged legacy composite row + a new override → 200",
    )
    fun `SYS-065 unchanged composite echo plus new override is accepted`() {
        val id = newComponent()
        val compositeId = seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        // Echo the composite verbatim (same id, same range, same marker children) AND add a new,
        // disjoint single-segment override. Before the fix the untouched composite echo re-ran range
        // validation and 400'd; now it is a no-op.
        patchComponent(
            id,
            """"fieldOverrides":[""" +
                echoVcsMarker(compositeId, "[1.0,2.0-1),(2.0-1,3.0)") + "," +
                """{"overriddenAttribute":"build.buildFilePath","versionRange":"[5.0,6.0)","value":"FileA"}""" +
                """]""",
        )
        val overrides = listFieldOverrides(id)
        val composite = overrides.first { it["id"].asText() == compositeId }
        assertEquals("[1.0,2.0-1),(2.0-1,3.0)", composite["versionRange"].asText(), "composite preserved byte-identical")
        assertTrue(overrides.any { it["overriddenAttribute"].asText() == "build.buildFilePath" }, "new override added")
    }

    @Test
    @DisplayName("SYS-065: a whitespace composite echoed verbatim → 200 and stays byte-identical")
    fun `SYS-065 whitespace composite echo is a no-op`() {
        val id = newComponent()
        val range = "[1.7, 2), [2.4.0,2.4.11]"
        val compositeId = seedCompositeMarker(id, range)
        patchComponent(id, """"fieldOverrides":[${echoVcsMarker(compositeId, range)}]""")
        val composite = listFieldOverrides(id).first { it["id"].asText() == compositeId }
        // Proves BOTH the normalized comparison (whitespace echo recognised as unchanged) AND that an
        // unchanged echo is not reassigned (the stored string keeps its original whitespace).
        assertEquals(range, composite["versionRange"].asText(), "stored range must keep its original whitespace")
    }

    @Test
    @DisplayName("SYS-065: composite CREATE via desired-set is still rejected (400)")
    fun `SYS-065 composite create still rejected`() {
        val id = newComponent()
        patchComponentExpectingBadRequest(
            id,
            """"fieldOverrides":[{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0),[3.0,4.0)","value":"X"}]""",
        )
    }

    @Test
    @DisplayName("SYS-065: changing a composite row's range (echo a different composite) is still validated (400)")
    fun `SYS-065 changing composite range still validated`() {
        val id = newComponent()
        val compositeId = seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        patchComponentExpectingBadRequest(
            id,
            """"fieldOverrides":[${echoVcsMarker(compositeId, "[1.0,2.0),[9.0,10.0)")}]""",
        )
    }

    @Test
    @DisplayName("SYS-065: value-only edit of a legacy composite (unchanged range, changed children) → 200")
    fun `SYS-065 value-only edit of composite is accepted`() {
        val id = newComponent()
        val range = "[1.0,2.0-1),(2.0-1,3.0)"
        val compositeId = seedCompositeMarker(id, range)
        patchComponent(
            id,
            """"fieldOverrides":[${echoVcsMarker(compositeId, range, vcsPath = "ssh://vcs/changed")}]""",
        )
        val composite = listFieldOverrides(id).first { it["id"].asText() == compositeId }
        assertEquals(range, composite["versionRange"].asText(), "range unchanged")
        assertEquals(
            "ssh://vcs/changed",
            composite["markerChildren"]["vcsEntries"][0]["vcsPath"].asText(),
            "the child edit must be applied",
        )
    }

    @Test
    @DisplayName("SYS-065: a valid range change on an existing override is validated AND persisted (200)")
    fun `SYS-065 valid range change is persisted`() {
        val id = newComponent()
        val overrideId = seedOverride(id, "build.buildFilePath", "[1.0,2.0)", "FileA")
        // Exercises the positive `!rangeUnchangedOnUpdate` branch: a genuinely changed range must be
        // BOTH validated AND assigned. A regression that validates but drops the assignment would leave
        // the stored range at [1.0,2.0) and fail the final assertion.
        patchComponent(
            id,
            """"fieldOverrides":[{"id":"$overrideId","overriddenAttribute":"build.buildFilePath",""" +
                """"versionRange":"[3.0,4.0)","value":"FileA"}]""",
        )
        val overrides = listFieldOverrides(id)
        assertEquals(1, overrides.size)
        assertEquals(overrideId, overrides[0]["id"].asText(), "same row updated in place, not recreated")
        assertEquals("[3.0,4.0)", overrides[0]["versionRange"].asText(), "the changed range must be persisted")
    }

    @Test
    @DisplayName("SYS-065: a NEW override intersecting an untouched composite sibling is still rejected (400)")
    fun `SYS-065 disjointness still enforced against untouched composite`() {
        val id = newComponent()
        // Composite covers [1.0,3.0) except 2.0-1. Echo it unchanged (no-op) AND add a new vcs.settings
        // override at [1.5,1.6) — inside the composite's first segment. The fix must NOT weaken the
        // disjoint-range rule: the new row is still validated against the untouched composite sibling.
        val compositeId = seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        patchComponentExpectingBadRequest(
            id,
            """"fieldOverrides":[""" +
                echoVcsMarker(compositeId, "[1.0,2.0-1),(2.0-1,3.0)") + "," +
                """{"overriddenAttribute":"vcs.settings","versionRange":"[1.5,1.6)",""" +
                """"markerChildren":{"vcsEntries":[{"name":"main","vcsPath":"ssh://vcs/new","repositoryType":"GIT"}]}}""" +
                """]""",
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Seed a composite (multi-segment) `vcs.settings` MARKER row directly via the repository — the
     *  public POST rejects composites, so a legacy DSL-imported composite can only be created here. */
    private fun seedCompositeMarker(
        componentId: String,
        range: String,
        vcsPath: String = "ssh://vcs/legacy",
    ): String {
        val component = componentRepository.findById(UUID.fromString(componentId)).orElseThrow()
        val row = ComponentConfigurationEntity(
            component = component,
            versionRange = range,
            overriddenAttribute = "vcs.settings",
            rowType = "MARKER",
        )
        row.vcsEntries.add(
            VcsSettingsEntryEntity(componentConfiguration = row, name = "main", vcsPath = vcsPath, repositoryType = "GIT"),
        )
        return configurationRepository.save(row).id!!.toString()
    }

    /** A desired-set entry that echoes a `vcs.settings` MARKER override by id. */
    private fun echoVcsMarker(
        id: String,
        range: String,
        vcsPath: String = "ssh://vcs/legacy",
    ): String =
        """{"id":"$id","overriddenAttribute":"vcs.settings","versionRange":"$range",""" +
            """"markerChildren":{"vcsEntries":[{"name":"main","vcsPath":"$vcsPath","repositoryType":"GIT"}]}}"""

    private fun patchComponentExpectingBadRequest(
        componentId: String,
        fieldsWithoutVersion: String,
    ) {
        val payload = """{"version":${currentVersion(componentId)},$fieldsWithoutVersion}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$componentId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    private fun newComponent(): String {
        val name = "fo-patch-${UUID.randomUUID().toString().take(8)}"
        val body = mvc
            .perform(
                post("/rest/api/4/components").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(
                    """{"name":"$name","componentOwner":"owner1",""" +
                        """"group":{"groupKey":"org.example.test","isFake":false},""" +
                        """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                ),
            ).andExpect(status().is2xxSuccessful)
            .andReturn()
            .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun seedOverride(
        componentId: String,
        attribute: String,
        range: String,
        value: String,
    ): String {
        val body = mvc
            .perform(
                post("/rest/api/4/components/$componentId/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"$attribute","versionRange":"$range","value":"$value"}"""),
            ).andExpect(status().is2xxSuccessful)
            .andReturn()
            .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun currentVersion(componentId: String): Long = getComponent(componentId)["version"].asLong()

    private fun getComponent(componentId: String): JsonNode {
        val body = mvc
            .perform(get("/rest/api/4/components/$componentId").with(adminJwt()))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun patchComponent(
        componentId: String,
        fieldsWithoutVersion: String,
    ) {
        val payload = """{"version":${currentVersion(componentId)},$fieldsWithoutVersion}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$componentId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isOk)
    }

    private fun listFieldOverrides(componentId: String): List<JsonNode> {
        val body = mvc
            .perform(get("/rest/api/4/components/$componentId/field-overrides").with(adminJwt()))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
        return objectMapper.readTree(body).toList().also { assertTrue(it.isEmpty() || it[0].has("id")) }
    }
}
