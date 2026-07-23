package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-066 — the one-off composite field-override split admin endpoint. Flag ENABLED here so the
 * conditionally-registered controller is wired. Composite rows are seeded directly via the repository
 * (the public POST rejects composites). ft-db = H2 + auto-migrate.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["components-registry.composite-override-split.enabled=true"],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class CompositeOverrideSplitAdminControllerTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var configurationRepository: ComponentConfigurationRepository

    @Autowired private lateinit var componentRepository: ComponentRepository

    init {
        // The ft-db profile's `components-registry.groovy-path` interpolates this system property.
        val testResourcesPath =
            Paths.get(CompositeOverrideSplitAdminControllerTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun isolate() {
        // The split endpoint scans the WHOLE DB; @DirtiesContext is per-class, so clear field-override
        // rows (BASE rows are harmless) between methods so each test's global counts are meaningful and
        // a fail-closed test's bad composite can't poison the next.
        val overrides = configurationRepository.findAll().filter { it.rowType == "MARKER" || it.rowType == "SCALAR_OVERRIDE" }
        configurationRepository.deleteAll(overrides)
    }

    @Test
    @DisplayName("SYS-066: dry-run previews the split and writes nothing")
    fun `SYS-066 dry-run previews without writing`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        val res = split(dryRun = true).andExpect(status().isOk).body()
        assertTrue(res["dryRun"].asBoolean())
        assertFalse(res["manifestToken"].asText().isBlank(), "a token is returned for a later write")
        assertEquals(1, res["rowsSplit"].asInt())
        assertEquals(2, res["segmentsCreated"].asInt())
        // Nothing written: the row is still the single composite.
        val rows = overridesOf(id)
        assertEquals(1, rows.size)
        assertEquals("[1.0,2.0-1),(2.0-1,3.0)", rows[0]["versionRange"].asText())
    }

    @Test
    @DisplayName("SYS-066: write splits the composite into single-segment rows with copied vcs payload")
    fun `SYS-066 write splits with copied payload`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)", vcsPath = "ssh://vcs/ufx")
        val token = split(dryRun = true).body()["manifestToken"].asText()
        split(dryRun = false, token = token).andExpect(status().isOk)
        val rows = overridesOf(id).sortedBy { it["versionRange"].asText() }
        assertEquals(2, rows.size, "composite replaced by its two segments")
        assertEquals(listOf("(2.0-1,3.0)", "[1.0,2.0-1)"), rows.map { it["versionRange"].asText() }.sorted())
        rows.forEach {
            assertEquals("vcs.settings", it["overriddenAttribute"].asText())
            assertEquals("ssh://vcs/ufx", it["markerChildren"]["vcsEntries"][0]["vcsPath"].asText(), "payload copied")
        }
    }

    @Test
    @DisplayName("SYS-066: exact-version composite splits into [x] rows")
    fun `SYS-066 exact-version composite splits`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.1.40],[1.1.49],[1.1.51]")
        writeSplit()
        assertEquals(
            listOf("[1.1.40]", "[1.1.49]", "[1.1.51]"),
            overridesOf(id).map { it["versionRange"].asText() }.sorted(),
        )
    }

    @Test
    @DisplayName("SYS-066: a second run is a no-op (idempotent)")
    fun `SYS-066 idempotent`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        writeSplit()
        val res = split(dryRun = true).andExpect(status().isOk).body()
        assertEquals(0, res["rowsSplit"].asInt(), "no composites remain")
        assertTrue(res["manifest"].isEmpty)
    }

    @Test
    @DisplayName("SYS-066: the BASE all-versions sentinel is never touched")
    fun `SYS-066 base sentinel untouched`() {
        val id = newComponent()
        val res = split(dryRun = true).andExpect(status().isOk).body()
        assertEquals(0, res["rowsSplit"].asInt(), "BASE (,0),[0,) is not a composite override")
    }

    @Test
    @DisplayName("SYS-066: a composite on a non-vcs.settings attribute aborts fail-closed (409), no writes")
    fun `SYS-066 non-vcs attribute aborts`() {
        val id = newComponent()
        seedCompositeScalar(id)
        split(dryRun = true).andExpect(status().isConflict)
        // Untouched.
        assertTrue(overridesOf(id).any { it["versionRange"].asText().contains("),") })
    }

    @Test
    @DisplayName("SYS-066: a self-overlapping composite aborts (409)")
    fun `SYS-066 self-overlapping aborts`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1,3),[2,4)")
        split(dryRun = true).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("SYS-066: a sibling collision with a different payload aborts and rolls back")
    fun `SYS-066 sibling collision different payload aborts`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)", vcsPath = "ssh://vcs/a")
        seedSimpleMarker(id, "[1.0,2.0-1)", vcsPath = "ssh://vcs/DIFFERENT")
        split(dryRun = true).andExpect(status().isConflict)
        // Rollback: composite + sibling both intact (3 rows: BASE excluded → 2 overrides).
        assertEquals(2, overridesOf(id).size)
    }

    @Test
    @DisplayName("SYS-066: a sibling collision with an identical payload skips that duplicate segment")
    fun `SYS-066 sibling collision identical payload skips`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)", vcsPath = "ssh://vcs/same")
        seedSimpleMarker(id, "[1.0,2.0-1)", vcsPath = "ssh://vcs/same")
        val res = split(dryRun = true).andExpect(status().isOk).body()
        assertEquals(1, res["segmentsSkippedAsDuplicate"].asInt())
        assertEquals(1, res["segmentsCreated"].asInt(), "only the non-duplicate segment is created")
        writeSplit()
        // The pre-existing sibling + the one newly-created segment; composite gone.
        assertEquals(
            listOf("(2.0-1,3.0)", "[1.0,2.0-1)"),
            overridesOf(id).map { it["versionRange"].asText() }.sorted(),
        )
    }

    @Test
    @DisplayName("SYS-066: a write without a manifestToken aborts (409)")
    fun `SYS-066 write without token aborts`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        split(dryRun = false).andExpect(status().isConflict)
        assertEquals(1, overridesOf(id).size, "no writes")
    }

    @Test
    @DisplayName("SYS-066: a stale manifestToken (data changed since the dry-run) aborts (409)")
    fun `SYS-066 stale token aborts`() {
        val id = newComponent()
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)")
        val token = split(dryRun = true).body()["manifestToken"].asText()
        // Data moves: another composite appears on a second component.
        val id2 = newComponent()
        seedCompositeMarker(id2, "[5.0,6.0-1),(6.0-1,7.0)")
        split(dryRun = false, token = token).andExpect(status().isConflict)
        assertEquals(1, overridesOf(id).size, "no writes after a stale token")
        assertEquals(1, overridesOf(id2).size)
    }

    @Test
    @DisplayName("SYS-066: a malformed composite range aborts (409)")
    fun `SYS-066 malformed range aborts`() {
        val id = newComponent()
        seedRawMarker(id, "[1,2")
        split(dryRun = true).andExpect(status().isConflict)
    }

    @Test
    @DisplayName("SYS-066: an identical AND a conflicting sibling on one segment both get checked (no early-break bypass)")
    fun `SYS-066 all intersecting siblings are inspected`() {
        val id = newComponent()
        // Segment [1.0,2.0-1) is intersected by TWO siblings: an exact identical duplicate AND a
        // partial-overlap row with a different payload. With order-independent checking, the conflicting
        // one must abort regardless of which sibling the DB enumerates first (guards the break→continue fix).
        seedCompositeMarker(id, "[1.0,2.0-1),(2.0-1,3.0)", vcsPath = "ssh://vcs/p")
        seedSimpleMarker(id, "[1.0,2.0-1)", vcsPath = "ssh://vcs/p") // exact duplicate of segment 1
        seedSimpleMarker(id, "[1.5,1.6)", vcsPath = "ssh://vcs/DIFFERENT") // partial overlap of segment 1
        split(dryRun = true).andExpect(status().isConflict)
        assertEquals(3, overridesOf(id).size, "rollback: composite + both siblings intact")
    }

    @Test
    @DisplayName("SYS-066: a composite yielding a factory-invalid segment aborts (409)")
    fun `SYS-066 composite with invalid segment aborts`() {
        val id = newComponent()
        // Regex-splittable but the second segment is a reversed range the VersionRangeFactory rejects;
        // discovery must factory-validate each segment and abort rather than write a bad row.
        seedCompositeMarker(id, "[1.0,2.0),[5.0,1.0)")
        split(dryRun = true).andExpect(status().isConflict)
        assertEquals(1, overridesOf(id).size, "no writes")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun ResultActions.body(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

    private fun split(
        dryRun: Boolean,
        token: String? = null,
    ): ResultActions {
        var url = "/rest/api/4/admin/field-overrides/split-composites?dryRun=$dryRun"
        if (token != null) url += "&manifestToken=$token"
        return mvc.perform(post(url).with(adminJwt()))
    }

    /** Dry-run to get the token, then write with it (asserts 200). */
    private fun writeSplit() {
        val token = split(dryRun = true).andExpect(status().isOk).body()["manifestToken"].asText()
        split(dryRun = false, token = token).andExpect(status().isOk)
    }

    private fun newComponent(): String {
        val name = "cos-${UUID.randomUUID().toString().take(8)}"
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

    private fun seedCompositeMarker(
        componentId: String,
        range: String,
        vcsPath: String = "ssh://vcs/legacy",
    ) = seedRawMarker(componentId, range, vcsPath)

    private fun seedSimpleMarker(
        componentId: String,
        range: String,
        vcsPath: String,
    ) = seedRawMarker(componentId, range, vcsPath)

    /** Seed a `vcs.settings` MARKER row (any versionRange, incl. composite/malformed) via the repo. */
    private fun seedRawMarker(
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

    /** Seed a SCALAR_OVERRIDE row with a composite range (out-of-scope for the split → abort). */
    private fun seedCompositeScalar(componentId: String): String {
        val component = componentRepository.findById(UUID.fromString(componentId)).orElseThrow()
        val row = ComponentConfigurationEntity(
            component = component,
            versionRange = "[1.0,2.0),[3.0,4.0)",
            overriddenAttribute = "build.buildFilePath",
            rowType = "SCALAR_OVERRIDE",
            buildFilePath = "pom.xml",
        )
        return configurationRepository.save(row).id!!.toString()
    }

    private fun overridesOf(componentId: String): List<JsonNode> =
        objectMapper
            .readTree(
                mvc
                    .perform(get("/rest/api/4/components/$componentId/field-overrides").with(adminJwt()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            ).toList()
}
