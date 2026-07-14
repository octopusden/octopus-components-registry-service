package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * CRS-A — v4 write-side clear semantics for aspect string scalars.
 *
 * Tri-state contract for the string scalars of the base configuration's
 * `build` / `escrow` / `jira` aspects, plus the top-level `vcsExternalRegistry`:
 *   - `null` / absent  → no-op (leave the column unchanged),
 *   - `""` (or blank)  → clear the column (persist NULL),
 *   - non-blank        → set verbatim.
 *
 * Before this change the write path used `?.let { X = it }`, so a `""` was
 * stored verbatim as an empty string and `null` was the only "don't touch"
 * signal — there was no way to clear a scalar from the editor. These round-trips
 * seed a component via the v4 POST endpoint, PATCH the fields to `""`, and read
 * them back as NULL. Also pins that create maps `""` to NULL, that `null`
 * remains a no-op, and that a cleared value audits as old→null.
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
class V4ClearSemanticsTest {
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
        val testResourcesPath =
            Paths.get(V4ClearSemanticsTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    // A unique jira project key so the (projectKey, versionPrefix) uniqueness rule
    // never collides with a sibling test or fixture data.
    private fun uniqueProjectKey() = "PK${UUID.randomUUID().toString().take(8).uppercase().replace("-", "")}"

    /** POST a component with the given raw baseConfiguration + top-level JSON fragments; returns the detail node. */
    private fun create(
        name: String,
        baseConfigJson: String,
        topLevelJson: String = "",
    ): JsonNode {
        val body =
            """{"name":"$name","componentOwner":"owner1",""" +
                """"group":{"groupKey":"org.example.test","isFake":false},$topLevelJson""" +
                """"baseConfiguration":$baseConfigJson}"""
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun getComponent(id: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun patch(
        id: String,
        version: Long,
        fieldsJson: String,
    ): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(
                    patch("/rest/api/4/components/$id")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"version":$version,$fieldsJson}"""),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    /** The BASE configuration row of a detail node. */
    private fun JsonNode.baseRow(): JsonNode = this["configurations"].first { it["rowType"].asText() == "BASE" }

    /** A scalar under `configurations[BASE].<aspect>.<field>`, or null when JSON null/absent. */
    private fun JsonNode.baseScalar(
        aspect: String,
        field: String,
    ): String? = baseRow()[aspect]?.get(field)?.takeUnless { it.isNull }?.asText()

    private fun version(node: JsonNode): Long = node["version"].asLong()

    // ------------------------------------------------------------------
    // jira aspect string scalars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH jira scalars to \"\" clears them to NULL (read back as null)")
    fun `jira scalars clear via empty string`() {
        val pk = uniqueProjectKey()
        val created =
            create(
                unique("clear_jira"),
                """{"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"$pk","versionPrefix":"pfx",""" +
                    """"minorVersionFormat":"${'$'}major.${'$'}minor","releaseVersionFormat":"${'$'}major.${'$'}minor.${'$'}service",""" +
                    """"buildVersionFormat":"${'$'}major.${'$'}minor.${'$'}service.${'$'}fix","lineVersionFormat":"Line.${'$'}major.${'$'}minor",""" +
                    """"versionFormat":"${'$'}versionPrefix-${'$'}baseVersionFormat"}}""",
            )
        val id = created["id"].asText()
        // Sanity: everything set on create.
        assertEquals(pk, created.baseScalar("jira", "projectKey"))
        assertEquals("pfx", created.baseScalar("jira", "versionPrefix"))

        val patched =
            patch(
                id,
                version(created),
                """"baseConfiguration":{"jira":{"projectKey":"","versionPrefix":"","minorVersionFormat":"",""" +
                    """"releaseVersionFormat":"","buildVersionFormat":"","lineVersionFormat":"","versionFormat":""}}""",
            )

        listOf(
            "projectKey",
            "versionPrefix",
            "minorVersionFormat",
            "releaseVersionFormat",
            "buildVersionFormat",
            "lineVersionFormat",
            "versionFormat",
        ).forEach { field ->
            assertNull(patched.baseScalar("jira", field), "jira.$field must be cleared to null, was ${patched.baseScalar("jira", field)}")
        }
        // Durable across a fresh read.
        assertNull(getComponent(id).baseScalar("jira", "buildVersionFormat"))
    }

    @Test
    @DisplayName("clearing jira.buildVersionFormat via \"\" leaves releaseVersionFormat intact (fallback precondition)")
    fun `clearing build format leaves release format`() {
        val created =
            create(
                unique("clear_buildfmt"),
                """{"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"${uniqueProjectKey()}",""" +
                    """"releaseVersionFormat":"${'$'}major.${'$'}minor.${'$'}service","buildVersionFormat":"${'$'}major.${'$'}minor.${'$'}service.${'$'}fix"}}""",
            )
        val id = created["id"].asText()
        val patched = patch(id, version(created), """"baseConfiguration":{"jira":{"buildVersionFormat":""}}""")
        assertNull(patched.baseScalar("jira", "buildVersionFormat"), "buildVersionFormat must be cleared")
        assertEquals(
            "\$major.\$minor.\$service",
            patched.baseScalar("jira", "releaseVersionFormat"),
            "releaseVersionFormat must be untouched — its value is what a cleared buildVersionFormat falls back to",
        )
    }

    // ------------------------------------------------------------------
    // build aspect string scalars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH build scalars to \"\" clears them to NULL")
    fun `build scalars clear via empty string`() {
        val created =
            create(
                unique("clear_build"),
                """{"build":{"buildSystem":"MAVEN","javaVersion":"17","mavenVersion":"3.9","buildFilePath":"pom.xml",""" +
                    """"gradleVersion":"8.6","projectVersion":"1.0","systemProperties":"-Dfoo=bar","buildTasks":"clean build"}}""",
            )
        val id = created["id"].asText()
        val patched =
            patch(
                id,
                version(created),
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN","javaVersion":"","mavenVersion":"","buildFilePath":"",""" +
                    """"gradleVersion":"","projectVersion":"","systemProperties":"","buildTasks":""}}""",
            )
        assertEquals("MAVEN", patched.baseScalar("build", "buildSystem"), "non-blank value stays set")
        assertNull(patched.baseScalar("build", "javaVersion"))
        assertNull(patched.baseScalar("build", "mavenVersion"))
        assertNull(patched.baseScalar("build", "buildFilePath"))
        assertNull(patched.baseScalar("build", "gradleVersion"))
        assertNull(patched.baseScalar("build", "projectVersion"))
        assertNull(patched.baseScalar("build", "systemProperties"))
        assertNull(patched.baseScalar("build", "buildTasks"))
    }

    // ------------------------------------------------------------------
    // escrow aspect string scalars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH free-text escrow scalars to \"\" clears them; the validated generation enum is left intact")
    fun `escrow scalars clear via empty string`() {
        val created =
            create(
                unique("clear_escrow"),
                """{"build":{"buildSystem":"MAVEN"},"escrow":{"generation":"AUTO","diskSpace":"500M",""" +
                    """"buildTask":"clean build","providedDependencies":"org.foo:bar","additionalSources":"src/extra",""" +
                    """"gradleIncludeConfigurations":"runtimeClasspath","gradleExcludeConfigurations":"testRuntime"}}""",
            )
        val id = created["id"].asText()
        // generation is a validated enum (not free-text) — echo it unchanged; the
        // free-text scalars carry "" to clear.
        val patched =
            patch(
                id,
                version(created),
                """"baseConfiguration":{"escrow":{"generation":"AUTO","diskSpace":"","buildTask":"","providedDependencies":"",""" +
                    """"additionalSources":"","gradleIncludeConfigurations":"","gradleExcludeConfigurations":""}}""",
            )
        assertEquals("AUTO", patched.baseScalar("escrow", "generation"), "the validated generation enum stays set")
        assertNull(patched.baseScalar("escrow", "diskSpace"))
        assertNull(patched.baseScalar("escrow", "buildTask"))
        assertNull(patched.baseScalar("escrow", "providedDependencies"))
        assertNull(patched.baseScalar("escrow", "additionalSources"))
        assertNull(patched.baseScalar("escrow", "gradleIncludeConfigurations"))
        assertNull(patched.baseScalar("escrow", "gradleExcludeConfigurations"))
    }

    // ------------------------------------------------------------------
    // top-level vcsExternalRegistry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH vcsExternalRegistry to \"\" clears it to NULL")
    fun `vcsExternalRegistry clears via empty string`() {
        val created =
            create(
                unique("clear_vcsreg"),
                """{"build":{"buildSystem":"MAVEN"}}""",
                topLevelJson = """"vcsExternalRegistry":"some-registry",""",
            )
        val id = created["id"].asText()
        assertEquals("some-registry", created["vcsExternalRegistry"].asText())

        val patched = patch(id, version(created), """"vcsExternalRegistry":""""")
        assertTrue(patched["vcsExternalRegistry"].isNull, "vcsExternalRegistry must be cleared to null")
        assertTrue(getComponent(id)["vcsExternalRegistry"].isNull, "clear must be durable across a fresh read")
    }

    // ------------------------------------------------------------------
    // null / absent remains a no-op (regression guard for the existing contract)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH with null scalar is still a no-op (leaves the value unchanged)")
    fun `null scalar is a no-op`() {
        val pk = uniqueProjectKey()
        val pk2 = uniqueProjectKey()
        val created =
            create(
                unique("noop_jira"),
                """{"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"$pk","releaseVersionFormat":"${'$'}major.${'$'}minor.${'$'}service"}}""",
            )
        val id = created["id"].asText()
        // PATCH an unrelated field; jira.releaseVersionFormat is absent → must be preserved.
        val patched = patch(id, version(created), """"baseConfiguration":{"jira":{"projectKey":"$pk2"}}""")
        assertEquals(pk2, patched.baseScalar("jira", "projectKey"))
        assertEquals(
            "\$major.\$minor.\$service",
            patched.baseScalar("jira", "releaseVersionFormat"),
            "an absent scalar must be left unchanged (null = don't touch)",
        )
    }

    @Test
    @DisplayName("PATCH with an EXPLICIT JSON null scalar is likewise a no-op (distinct from \"\")")
    fun `explicit json null scalar is a no-op`() {
        val pk = uniqueProjectKey()
        val created =
            create(
                unique("noop_explicit_null"),
                """{"build":{"buildSystem":"MAVEN","javaVersion":"17"},""" +
                    """"jira":{"projectKey":"$pk","releaseVersionFormat":"${'$'}major.${'$'}minor.${'$'}service"}}""",
            )
        val id = created["id"].asText()
        // Explicit JSON nulls (NOT absent fields) — the tri-state's null branch must
        // leave both values untouched, in contrast to "" which would clear them.
        val patched =
            patch(
                id,
                version(created),
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN","javaVersion":null},""" +
                    """"jira":{"projectKey":"$pk","releaseVersionFormat":null}}""",
            )
        assertEquals("17", patched.baseScalar("build", "javaVersion"), "explicit null must not clear")
        assertEquals(
            "\$major.\$minor.\$service",
            patched.baseScalar("jira", "releaseVersionFormat"),
            "explicit null must not clear",
        )
    }

    // ------------------------------------------------------------------
    // create maps "" to null (consistency with PATCH clear)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create maps \"\" scalars to NULL (treated as absent)")
    fun `create maps empty string to null`() {
        val pk = uniqueProjectKey()
        val created =
            create(
                unique("create_empty"),
                """{"build":{"buildSystem":"MAVEN","javaVersion":""},"jira":{"projectKey":"$pk","versionPrefix":""}}""",
                topLevelJson = """"vcsExternalRegistry":"",""",
            )
        assertNull(created.baseScalar("build", "javaVersion"), "create must map build.javaVersion \"\" to null")
        assertNull(created.baseScalar("jira", "versionPrefix"), "create must map jira.versionPrefix \"\" to null")
        assertTrue(created["vcsExternalRegistry"].isNull, "create must map vcsExternalRegistry \"\" to null")
        assertEquals(pk, created.baseScalar("jira", "projectKey"), "non-blank stays set")
    }

    // ------------------------------------------------------------------
    // audit records old -> null on clear
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearing a scalar via \"\" records an old->null audit diff")
    fun `clear audits old to null`() {
        val created =
            create(
                unique("audit_clear"),
                """{"build":{"buildSystem":"MAVEN","mavenVersion":"3.9"}}""",
            )
        val id = created["id"].asText()
        patch(id, version(created), """"baseConfiguration":{"build":{"buildSystem":"MAVEN","mavenVersion":""}}""")

        val history =
            objectMapper
                .readTree(
                    mvc
                        .perform(get("/rest/api/4/audit/Component/$id").with(adminJwt()).param("size", "500"))
                        .andExpect(status().isOk)
                        .andReturn()
                        .response.contentAsString,
                )["content"]
                .toList()
        val diff =
            history
                .filter { it["action"].asText() == "UPDATE" }
                .map { it.path("changeDiff") }
                .firstOrNull { it.isObject && it.has("build.mavenVersion") }
                ?.get("build.mavenVersion")
                ?: error("expected an UPDATE audit row carrying build.mavenVersion: $history")
        assertEquals("3.9", diff.path("old").asText(), "audit old must be the pre-clear value")
        assertTrue(diff.path("new").isNull, "audit new must be null on clear, was ${diff.path("new")}")
    }

    // ------------------------------------------------------------------
    // P2: per-range scalar override with a blank string value is REJECTED 400
    // (an override row stores its value verbatim — no clear semantics; DELETE
    // removes it). Contrast with the BASE row where "" clears (tested above).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("per-range string scalar override with a blank value is rejected 400 (base \"\" clears, override \"\" does not)")
    fun `blank override value is rejected`() {
        val created = create(unique("override_blank"), """{"build":{"buildSystem":"MAVEN"}}""")
        val id = created["id"].asText()

        // "" for a string-typed scalar override → 400 (not stored, not a clear).
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"jira.buildVersionFormat","versionRange":"[1.0,2.0)","value":""}"""),
            ).andExpect(status().isBadRequest)

        // whitespace-only is likewise blank → 400.
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"   "}"""),
            ).andExpect(status().isBadRequest)

        // sanity: a non-blank override value is still accepted.
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"build.buildFilePath","versionRange":"[1.0,2.0)","value":"pom.xml"}"""),
            ).andExpect(status().is2xxSuccessful)
    }

    @Test
    @DisplayName("blank override value is rejected on standalone PATCH and on the component-PATCH desired-set too")
    fun `blank override value is rejected on update paths`() {
        val created = create(unique("override_blank_upd"), """{"build":{"buildSystem":"MAVEN"}}""")
        val id = created["id"].asText()

        // Seed a valid override, keep its id for the standalone PATCH path.
        val overrideId =
            objectMapper
                .readTree(
                    mvc
                        .perform(
                            post("/rest/api/4/components/$id/field-overrides")
                                .with(adminJwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    """{"overriddenAttribute":"jira.buildVersionFormat",""" +
                                        """"versionRange":"[1.0,2.0)","value":"${'$'}major.${'$'}minor"}""",
                                ),
                        ).andExpect(status().is2xxSuccessful)
                        .andReturn()
                        .response.contentAsString,
                )["id"]
                .asText()

        // Standalone PATCH of the override with a blank value → 400 (same guard as POST).
        mvc
            .perform(
                patch("/rest/api/4/components/$id/field-overrides/$overrideId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"value":"   "}"""),
            ).andExpect(status().isBadRequest)

        // Component-PATCH desired-set branch: a blank value inside fieldOverrides → 400,
        // and the PATCH must not partially apply.
        val versionNow = version(getComponent(id))
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":$versionNow,"fieldOverrides":[{"overriddenAttribute":"jira.buildVersionFormat",""" +
                            """"versionRange":"[1.0,2.0)","value":""}]}""",
                    ),
            ).andExpect(status().isBadRequest)

        // The seeded override survives both rejected writes unchanged.
        val overrides =
            objectMapper.readTree(
                mvc
                    .perform(get("/rest/api/4/components/$id/field-overrides").with(adminJwt()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        val row = overrides.first { it["id"].asText() == overrideId }
        assertEquals("\$major.\$minor", row["value"].asText(), "rejected blank writes must not alter the override")
    }

    // ------------------------------------------------------------------
    // P3.1: the two enum-validated scalars are NOT clearable via "" — 400 in
    // both create and PATCH flows (they are excluded from the clear rule).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create with build.buildSystem or escrow.generation = \"\" is rejected 400 (validated enums)")
    fun `create rejects blank enum scalars`() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"${unique("cr_bs")}","componentOwner":"owner1",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":""}}}""",
                    ),
            ).andExpect(status().isBadRequest)

        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"${unique("cr_gen")}","componentOwner":"owner1",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},"escrow":{"generation":""}}}""",
                    ),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("PATCH build.buildSystem or escrow.generation = \"\" is rejected 400 (validated enums)")
    fun `patch rejects blank enum scalars`() {
        val created =
            create(unique("patch_enum"), """{"build":{"buildSystem":"MAVEN"},"escrow":{"generation":"AUTO"}}""")
        val id = created["id"].asText()

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(created)},"baseConfiguration":{"build":{"buildSystem":""}}}"""),
            ).andExpect(status().isBadRequest)

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(created)},"baseConfiguration":{"escrow":{"generation":""}}}"""),
            ).andExpect(status().isBadRequest)
    }

    // ------------------------------------------------------------------
    // P3.2: a field-config-hidden scalar ignores an incoming "" entirely —
    // the hidden gate strips the write before the clear rule runs, so "" is
    // NOT a clear (the value is preserved).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hidden vcsExternalRegistry ignores an incoming \"\" (no clear — value preserved)")
    fun `hidden field ignores empty string`() {
        // create sets the value (create does not gate on hidden); PATCH does.
        val created =
            create(
                unique("hidden_vcsreg"),
                """{"build":{"buildSystem":"MAVEN"}}""",
                topLevelJson = """"vcsExternalRegistry":"keep-me",""",
            )
        val id = created["id"].asText()
        assertEquals("keep-me", created["vcsExternalRegistry"].asText())

        try {
            // Mark component.vcsExternalRegistry hidden via the field-config cache row.
            seedFieldConfig(
                mapOf("component" to mapOf("vcsExternalRegistry" to mapOf("visibility" to "hidden"))),
            )
            // PATCH "" — would clear if visible; hidden gate must strip it (no clear).
            patch(id, version(created), """"vcsExternalRegistry":""""")
            assertEquals(
                "keep-me",
                getComponent(id)["vcsExternalRegistry"].asText(),
                "hidden field must ignore the incoming \"\" — the clear must NOT happen",
            )
        } finally {
            // Reset so the persistent field-config row doesn't leak into sibling tests.
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
