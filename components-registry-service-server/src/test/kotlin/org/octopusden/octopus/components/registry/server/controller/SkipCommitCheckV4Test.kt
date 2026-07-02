package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
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
 * CRS-C — v4 write/read surface of the dedicated `skipCommitCheck` flag (Q12) and the Q13
 * WHISKEY exclusion rule.
 *
 * Covers: create/PATCH/read round-trip of the boolean (with the PATCH-null no-op convention),
 * the default (absent → false), the audit snapshot, and the 422 rejection when the flag would
 * coexist with an effective BASE build system of WHISKEY — on create AND on every PATCH
 * transition (flag→true while WHISKEY, buildSystem→WHISKEY while flag true).
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
class SkipCommitCheckV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    init {
        // application-common.yml's work-dir/groovy-path placeholders resolve against this;
        // point it at the packaged test fixtures (parent of /expected-data), as the sibling
        // ft-db integration tests do.
        val testResourcesPath =
            Paths.get(SkipCommitCheckV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun uniqueProjectKey() = "PK${UUID.randomUUID().toString().take(8).uppercase().replace("-", "")}"

    private fun createBody(name: String, baseConfigJson: String, topLevelJson: String = ""): String =
        """{"name":"$name","componentOwner":"owner1",""" +
            """"group":{"groupKey":"org.example.test","isFake":false},$topLevelJson""" +
            """"baseConfiguration":$baseConfigJson}"""

    /** POST expecting 201; returns the detail node. */
    private fun create(name: String, baseConfigJson: String, topLevelJson: String = ""): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, baseConfigJson, topLevelJson)),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString,
        )

    private fun getComponent(id: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )

    /** PATCH expecting 200; returns the detail node. */
    private fun patch(id: String, version: Long, fieldsJson: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(
                    patch("/rest/api/4/components/$id")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":$version,$fieldsJson}"""),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )

    private fun version(node: JsonNode): Long = node["version"].asLong()

    private fun JsonNode.baseScalar(aspect: String, field: String): String? =
        this["configurations"].first { it["rowType"].asText() == "BASE" }[aspect]?.get(field)?.takeUnless { it.isNull }?.asText()

    // ------------------------------------------------------------------
    // round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create with skipCommitCheck=true (non-WHISKEY) persists the flag; registry stays independent/null")
    fun `create true round-trips`() {
        val created =
            create(
                unique("scc_true"),
                """{"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"${uniqueProjectKey()}"}}""",
                topLevelJson = """"skipCommitCheck":true,""",
            )
        assertTrue(created["skipCommitCheck"].asBoolean(), "create must persist skipCommitCheck=true")
        assertTrue(created["vcsExternalRegistry"].isNull, "no registry was supplied — stays null (flag is a separate field)")
        assertTrue(getComponent(created["id"].asText())["skipCommitCheck"].asBoolean(), "durable across a fresh read")
    }

    @Test
    @DisplayName("skipCommitCheck defaults to false when absent on create")
    fun `default false`() {
        val created = create(unique("scc_default"), """{"build":{"buildSystem":"MAVEN"}}""")
        assertFalse(created["skipCommitCheck"].asBoolean(), "absent skipCommitCheck defaults to false")
    }

    @Test
    @DisplayName("PATCH toggles skipCommitCheck true↔false; null is a no-op")
    fun `patch toggles and null noop`() {
        val created = create(unique("scc_toggle"), """{"build":{"buildSystem":"MAVEN"}}""")
        val id = created["id"].asText()
        assertFalse(created["skipCommitCheck"].asBoolean())

        val on = patch(id, version(created), """"skipCommitCheck":true""")
        assertTrue(on["skipCommitCheck"].asBoolean(), "PATCH true sets the flag")

        // null = don't touch: PATCH an unrelated field, flag must survive.
        val noop = patch(id, version(on), """"clientCode":"C1"""")
        assertTrue(noop["skipCommitCheck"].asBoolean(), "PATCH with skipCommitCheck absent (null) leaves it unchanged")

        val off = patch(id, version(noop), """"skipCommitCheck":false""")
        assertFalse(off["skipCommitCheck"].asBoolean(), "PATCH false clears the flag")
        assertFalse(getComponent(id)["skipCommitCheck"].asBoolean(), "durable across a fresh read")
    }

    @Test
    @DisplayName("a WHISKEY component with skipCommitCheck=false is accepted (flag off is always fine)")
    fun `whiskey flag off ok`() {
        val created =
            create(unique("scc_whiskey_off"), """{"build":{"buildSystem":"WHISKEY"}}""")
        assertFalse(created["skipCommitCheck"].asBoolean())
        assertEquals("WHISKEY", created.baseScalar("build", "buildSystem"))
    }

    // ------------------------------------------------------------------
    // Q13 — WHISKEY exclusion (422)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create WHISKEY + skipCommitCheck=true is rejected 422")
    fun `create whiskey with flag rejected`() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        createBody(
                            unique("scc_whiskey_true"),
                            """{"build":{"buildSystem":"WHISKEY"}}""",
                            topLevelJson = """"skipCommitCheck":true,""",
                        ),
                    ),
            ).andExpect(status().isUnprocessableEntity)
    }

    @Test
    @DisplayName("PATCH flag→true on an existing WHISKEY component is rejected 422")
    fun `patch flag true while whiskey rejected`() {
        val created = create(unique("scc_whiskey_patch"), """{"build":{"buildSystem":"WHISKEY"}}""")
        mvc
            .perform(
                patch("/rest/api/4/components/${created["id"].asText()}")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(created)},"skipCommitCheck":true}"""),
            ).andExpect(status().isUnprocessableEntity)
    }

    @Test
    @DisplayName("PATCH buildSystem→WHISKEY while the flag is already true is rejected 422 (combined post-patch state)")
    fun `patch buildsystem to whiskey while flag true rejected`() {
        val created =
            create(
                unique("scc_toggle_bs"),
                """{"build":{"buildSystem":"MAVEN"}}""",
                topLevelJson = """"skipCommitCheck":true,""",
            )
        assertTrue(created["skipCommitCheck"].asBoolean())
        mvc
            .perform(
                patch("/rest/api/4/components/${created["id"].asText()}")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":${version(created)},"baseConfiguration":{"build":{"buildSystem":"WHISKEY"}}}""",
                    ),
            ).andExpect(status().isUnprocessableEntity)
    }

    // ------------------------------------------------------------------
    // audit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toggling skipCommitCheck records a false→true audit diff")
    fun `audit records flag change`() {
        val created = create(unique("scc_audit"), """{"build":{"buildSystem":"MAVEN"}}""")
        val id = created["id"].asText()
        patch(id, version(created), """"skipCommitCheck":true""")

        val history =
            objectMapper.readTree(
                mvc
                    .perform(get("/rest/api/4/audit/Component/$id").with(adminJwt()).param("size", "500"))
                    .andExpect(status().isOk)
                    .andReturn().response.contentAsString,
            )["content"].toList()
        val diff =
            history
                .filter { it["action"].asText() == "UPDATE" }
                .map { it.path("changeDiff") }
                .firstOrNull { it.isObject && it.has("skipCommitCheck") }
                ?.get("skipCommitCheck")
                ?: error("expected an UPDATE audit row carrying skipCommitCheck: $history")
        assertEquals(false, diff.path("old").asBoolean(), "audit old must be the pre-toggle value")
        assertEquals(true, diff.path("new").asBoolean(), "audit new must be the toggled value")
    }

    // ------------------------------------------------------------------
    // the legacy NOT_AVAILABLE sentinel must never be stored via v4
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create with vcsExternalRegistry=\"NOT_AVAILABLE\" is rejected 422 (use skipCommitCheck)")
    fun `create rejects sentinel registry`() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        createBody(
                            unique("scc_sentinel_create"),
                            """{"build":{"buildSystem":"MAVEN"}}""",
                            topLevelJson = """"vcsExternalRegistry":"NOT_AVAILABLE",""",
                        ),
                    ),
            ).andExpect(status().isUnprocessableEntity)
    }

    @Test
    @DisplayName("PATCH vcsExternalRegistry=\"NOT_AVAILABLE\" is rejected 422 (use skipCommitCheck)")
    fun `patch rejects sentinel registry`() {
        val created = create(unique("scc_sentinel_patch"), """{"build":{"buildSystem":"MAVEN"}}""")
        mvc
            .perform(
                patch("/rest/api/4/components/${created["id"].asText()}")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(created)},"vcsExternalRegistry":"NOT_AVAILABLE"}"""),
            ).andExpect(status().isUnprocessableEntity)
    }

    // ------------------------------------------------------------------
    // hidden component.skipCommitCheck is stripped on create (→ default false)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hidden component.skipCommitCheck strips an incoming true on create (saved false)")
    fun `hidden flag stripped on create`() {
        try {
            seedFieldConfig(mapOf("component" to mapOf("skipCommitCheck" to mapOf("visibility" to "hidden"))))
            val created =
                create(
                    unique("scc_hidden"),
                    """{"build":{"buildSystem":"MAVEN"}}""",
                    topLevelJson = """"skipCommitCheck":true,""",
                )
            assertFalse(
                created["skipCommitCheck"].asBoolean(),
                "a hidden skipCommitCheck must be stripped to its false default on create",
            )
            assertFalse(getComponent(created["id"].asText())["skipCommitCheck"].asBoolean(), "durable across a fresh read")
        } finally {
            seedFieldConfig(emptyMap())
        }
    }

    /** Seed the `field-config` cache row directly (mirrors ConfigSyncService's writer). */
    private fun seedFieldConfig(value: Map<String, Any?>) {
        val entity =
            registryConfigRepository.findById("field-config").orElse(RegistryConfigEntity(key = "field-config"))
        entity.value = value
        registryConfigRepository.save(entity)
    }
}
