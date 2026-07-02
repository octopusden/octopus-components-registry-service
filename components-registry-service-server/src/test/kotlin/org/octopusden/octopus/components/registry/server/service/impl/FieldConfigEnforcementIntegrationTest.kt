package org.octopusden.octopus.components.registry.server.service.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.RegistryConfigEntity
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Risk-1 mitigation — end-to-end test for [FieldConfigService].
 *
 * The unit suite (`FieldConfigServiceTest`) covers the visibility resolver
 * in isolation against a Mockito stub. This test closes the Spring-DI /
 * JPA / transaction loop:
 *
 *   1. Pick an existing component from the ft-db seed.
 *   2. Seed a `field-config` cache row directly via [RegistryConfigRepository]
 *      that sets `component.displayName.visibility = "hidden"`. (The blob is now
 *      code-as-config; the admin PUT writer is gone — see ConfigSyncService — so
 *      the test writes the cache the same way the sync would.)
 *   3. PATCH the component with a new `displayName` (as admin — the seed
 *      component has no owner/RM/SC, so a plain editor would be 403'd by the
 *      per-component edit gate; field-config stripping is principal-agnostic).
 *   4. Assert the stored `displayName` is unchanged — the value was
 *      silently stripped at the service layer per the Risk-1 contract.
 *
 * `@DirtiesContext` ensures the realm-scoped `field-config` row written
 * here doesn't leak into other tests in the same suite.
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
class FieldConfigEnforcementIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var registryConfigRepository: RegistryConfigRepository

    /** Seed the `field-config` cache row directly — replaces the removed admin PUT writer. */
    private fun seedFieldConfig(value: Map<String, Any?>) {
        val entity = registryConfigRepository.findById("field-config").orElse(RegistryConfigEntity(key = "field-config"))
        entity.value = value
        registryConfigRepository.save(entity)
    }

    init {
        val testResourcesPath =
            Paths
                .get(FieldConfigEnforcementIntegrationTest::class.java.getResource("/expected-data")!!.toURI())
                .parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun firstComponent(): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(editorJwt()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val root = objectMapper.readTree(body)
        val content = root.path("content")
        assertTrue(content.isArray && content.size() > 0)
        return content[0]
    }

    private fun getComponent(id: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    @Test
    @DisplayName("hidden displayName via field-config → PATCH with new displayName silently stripped")
    fun hiddenDisplayName_isStripped() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val originalDetail = getComponent(id)
        val originalDisplayName = originalDetail["displayName"].asText("")
        val version = originalDetail["version"].asLong()

        // Step 1: configure displayName=hidden by seeding the field-config cache row.
        seedFieldConfig(
            mapOf(
                "component" to
                    mapOf(
                        "displayName" to mapOf("visibility" to "hidden"),
                    ),
            ),
        )

        // Step 2: PATCH with a brand-new displayName (as admin — see class doc).
        val attempted = "ATTEMPTED-CHANGE-${System.nanoTime()}"
        assertNotEquals(originalDisplayName, attempted, "test data invariant")
        val patchPayload =
            mapOf(
                "version" to version,
                "displayName" to attempted,
            )
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(patchPayload)),
            ).andExpect(status().is2xxSuccessful)

        // Step 3: assert the displayName did NOT change. The hidden gate
        // silently stripped the value before mutation.
        val updated = getComponent(id)
        assertEquals(
            originalDisplayName,
            updated["displayName"].asText(""),
            "displayName should be unchanged: hidden field write was silently stripped",
        )
    }

    @Test
    @DisplayName("editable displayName (explicit field-config) → PATCH applies normally")
    fun editableDisplayName_appliesNormally() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val originalDetail = getComponent(id)
        val version = originalDetail["version"].asLong()

        // Make the case order-independent: write an explicit
        // `displayName.visibility = "editable"` instead of relying on the
        // field-config row being absent. JUnit doesn't guarantee method
        // order, and the `hiddenDisplayName_isStripped` case writes a
        // persistent `field-config` row whose `hidden` value would mask
        // this test's contract if it ran first.
        seedFieldConfig(
            mapOf(
                "component" to
                    mapOf(
                        "displayName" to mapOf("visibility" to "editable"),
                    ),
            ),
        )

        val attempted = "EDITABLE-CHANGE-${System.nanoTime()}"
        val patchPayload =
            mapOf(
                "version" to version,
                "displayName" to attempted,
            )
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(patchPayload)),
            ).andExpect(status().is2xxSuccessful)

        val updated = getComponent(id)
        assertEquals(
            attempted,
            updated["displayName"].asText(""),
            "displayName should reflect the patch when field-config marks it editable",
        )
    }

    // ================================================================
    // CRS-B — editability axis (all | adminOnly | none) write-enforcement
    // ================================================================

    private fun uniqueProjectKey() = "PK${UUID.randomUUID().toString().take(8).uppercase().replace("-", "")}"

    /** BASE row of a detail node; its jira.technical boolean. */
    private fun JsonNode.baseRow(): JsonNode = this["configurations"].first { it["rowType"].asText() == "BASE" }

    private fun JsonNode.jiraTechnical(): Boolean = baseRow()["jira"]["technical"].asBoolean()

    private fun JsonNode.baseJira(field: String): String? =
        baseRow()["jira"]?.get(field)?.takeUnless { it.isNull }?.asText()

    /**
     * Create a component OWNED BY the editor "bob" (so `editorJwt()` passes the
     * per-component edit gate) with a known `jira.technical` + projectKey and an
     * optional top-level `vcsExternalRegistry`. Created as admin (bypasses all gates).
     */
    private fun createOwnedByBob(
        technical: Boolean = false,
        projectKey: String = uniqueProjectKey(),
        vcsExternalRegistry: String? = null,
    ): JsonNode {
        val registryFragment = vcsExternalRegistry?.let { """"vcsExternalRegistry":"$it",""" } ?: ""
        val body =
            """{"name":"crsB_${UUID.randomUUID().toString().take(8)}","componentOwner":"bob",""" +
                registryFragment +
                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                """"jira":{"projectKey":"$projectKey","technical":$technical}}}"""
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun patchRaw(
        who: RequestPostProcessor,
        id: String,
        bodyJson: String,
    ) = mvc.perform(
        patch("/rest/api/4/components/$id")
            .with(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyJson),
    )

    /** Seed a field-config, run [block], then reset the row so it can't leak into sibling tests. */
    private fun withFieldConfig(
        value: Map<String, Any?>,
        block: () -> Unit,
    ) {
        seedFieldConfig(value)
        try {
            block()
        } finally {
            seedFieldConfig(emptyMap())
        }
    }

    @Test
    @DisplayName("adminOnly jira.technical: non-admin editor FLIPPING the value → 403")
    fun adminOnlyTechnical_nonAdminFlip_forbidden() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            patchRaw(
                editorJwt(), // bob = owner, but lacks EDIT_ANY_COMPONENT
                id,
                """{"version":$version,"baseConfiguration":{"jira":{"technical":true}}}""",
            ).andExpect(status().isForbidden)
            // Value unchanged (transaction rolled back).
            assertEquals(false, getComponent(id).jiraTechnical())
        }
    }

    @Test
    @DisplayName("adminOnly jira.technical: non-admin editor ECHOING the unchanged value → 2xx (combined-save tolerance)")
    fun adminOnlyTechnical_nonAdminEcho_ok() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            patchRaw(
                editorJwt(),
                id,
                // Echo current value (false) — the Portal's combined Save sends the whole slice.
                """{"version":$version,"baseConfiguration":{"jira":{"technical":false}}}""",
            ).andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("adminOnly jira.technical: admin FLIPPING the value → 2xx and persisted")
    fun adminOnlyTechnical_adminFlip_ok() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            patchRaw(
                adminJwt(),
                id,
                """{"version":$version,"baseConfiguration":{"jira":{"technical":true}}}""",
            ).andExpect(status().is2xxSuccessful)
            assertEquals(true, getComponent(id).jiraTechnical())
        }
    }

    @Test
    @DisplayName("readonly jira.projectKey: admin CHANGING → 422; ECHOING unchanged → 2xx")
    fun readonlyProjectKey_changeRejected_echoOk() {
        val pk = uniqueProjectKey()
        val created = createOwnedByBob(projectKey = pk)
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("jira" to mapOf("projectKey" to mapOf("visibility" to "readonly")))) {
            // readonly is unified with editable:none — non-editable even for admin.
            patchRaw(
                adminJwt(),
                id,
                """{"version":$version,"baseConfiguration":{"jira":{"projectKey":"${uniqueProjectKey()}"}}}""",
            ).andExpect(status().isUnprocessableEntity)
            assertEquals(pk, getComponent(id).baseJira("projectKey"))

            // Echo of the unchanged value is tolerated.
            patchRaw(
                adminJwt(),
                id,
                """{"version":$version,"baseConfiguration":{"jira":{"projectKey":"$pk"}}}""",
            ).andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("hidden precedence: a hidden+adminOnly field is silently STRIPPED for a non-admin, never 403")
    fun hiddenBeatsAdminOnly_stripNotReject() {
        val created = createOwnedByBob(vcsExternalRegistry = "orig-registry")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(
            mapOf("component" to mapOf("vcsExternalRegistry" to mapOf("visibility" to "hidden", "editable" to "adminOnly"))),
        ) {
            // Non-admin owner tries to change a hidden field: hidden wins → silent strip, 2xx.
            patchRaw(
                editorJwt(),
                id,
                """{"version":$version,"vcsExternalRegistry":"changed-registry"}""",
            ).andExpect(status().is2xxSuccessful)
            assertEquals(
                "orig-registry",
                getComponent(id)["vcsExternalRegistry"].asText(),
                "hidden field must be stripped (unchanged), not rejected",
            )
        }
    }

    @Test
    @DisplayName("CREATE: non-admin supplying an adminOnly value (jira.technical) → 403")
    fun create_nonAdminSuppliesAdminOnly_forbidden() {
        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            val body =
                """{"name":"crsB_create_${UUID.randomUUID().toString().take(8)}","componentOwner":"bob",""" +
                    """"group":{"groupKey":"org.example.test","isFake":false},""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                    """"jira":{"projectKey":"${uniqueProjectKey()}","technical":true}}}"""
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(editorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isForbidden)
        }
    }

    @Test
    @DisplayName("CREATE: non-admin OMITTING the adminOnly field → 2xx (server default applies)")
    fun create_nonAdminOmitsAdminOnly_ok() {
        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            val body =
                """{"name":"crsB_create_${UUID.randomUUID().toString().take(8)}","componentOwner":"bob",""" +
                    """"group":{"groupKey":"org.example.test","isFake":false},""" +
                    """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                    """"jira":{"projectKey":"${uniqueProjectKey()}"}}}"""
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(editorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
        }
    }

    @Test
    @DisplayName("OVERRIDE: non-admin creating a jira.technical override → 403; admin → 201")
    fun override_adminOnlyField_gated() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            val overrideBody = """{"overriddenAttribute":"jira.technical","versionRange":"[1.0,2.0)","value":true}"""

            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(editorJwt()) // bob = owner, no EDIT_ANY_COMPONENT
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overrideBody),
                ).andExpect(status().isForbidden)

            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(overrideBody),
                ).andExpect(status().isCreated)
        }
    }

    @Test
    @DisplayName("OVERRIDE UPDATE: non-admin CHANGING a jira.technical override value → 403; ECHOING it → 2xx")
    fun override_update_valueChange_gated() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()

        // Create the override as admin while the field is still editable (unseeded).
        val overrideResponse =
            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"overriddenAttribute":"jira.technical","versionRange":"[1.0,2.0)","value":true}"""),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val overrideId = objectMapper.readTree(overrideResponse)["id"].asText()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            // Non-admin owner flips the override value → change → 403.
            mvc
                .perform(
                    patch("/rest/api/4/components/$id/field-overrides/$overrideId")
                        .with(editorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"value":false}"""),
                ).andExpect(status().isForbidden)

            // Non-admin owner echoes the unchanged value → snapshot-equal → 2xx.
            mvc
                .perform(
                    patch("/rest/api/4/components/$id/field-overrides/$overrideId")
                        .with(editorJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"value":true}"""),
                ).andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("adminOnly string: non-admin PADDED echo (\"  x  \" vs stored \"x\") → 403 (whitespace is a change)")
    fun adminOnlyString_paddedEcho_forbidden() {
        val created = createOwnedByBob(vcsExternalRegistry = "x")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("component" to mapOf("vcsExternalRegistry" to mapOf("editable" to "adminOnly")))) {
            // A padded value is NOT the stored value — the write would persist "  x  " verbatim,
            // so it must be treated as a change and rejected for a non-admin.
            patchRaw(editorJwt(), id, """{"version":$version,"vcsExternalRegistry":"  x  "}""")
                .andExpect(status().isForbidden)
            assertEquals("x", getComponent(id)["vcsExternalRegistry"].asText())
        }
    }

    @Test
    @DisplayName("adminOnly string: change-detection norm mirrors clearBlankScalar over {\"\",\"  \",\"x\",\"  x  \"}")
    fun adminOnlyString_normParityWithStorage() {
        // Stored value is "x". A non-admin echo is allowed iff clearBlankScalar(incoming) ==
        // clearBlankScalar("x"): only a verbatim "x" is a no-op; "" / "  " (clear→null) and
        // "  x  " (verbatim, whitespace-significant) are all real changes → 403.
        val created = createOwnedByBob(vcsExternalRegistry = "x")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("component" to mapOf("vcsExternalRegistry" to mapOf("editable" to "adminOnly")))) {
            // Changes (403) — these all leave the transaction rolled back, so `version` stays valid.
            for (changing in listOf("", "  ", "  x  ")) {
                patchRaw(editorJwt(), id, """{"version":$version,"vcsExternalRegistry":"$changing"}""")
                    .andExpect(status().isForbidden)
            }
            assertEquals("x", getComponent(id)["vcsExternalRegistry"].asText())

            // No-op echo of the exact stored value → allowed.
            patchRaw(editorJwt(), id, """{"version":$version,"vcsExternalRegistry":"x"}""")
                .andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("OVERRIDE DELETE: non-admin deleting a jira.technical override → 403; admin → 204")
    fun override_delete_gated() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()

        val overrideResponse =
            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"overriddenAttribute":"jira.technical","versionRange":"[1.0,2.0)","value":true}"""),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val overrideId = objectMapper.readTree(overrideResponse)["id"].asText()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("editable" to "adminOnly")))) {
            // Direct DELETE by a non-admin must be gated the same as the combined-save path.
            mvc
                .perform(delete("/rest/api/4/components/$id/field-overrides/$overrideId").with(editorJwt()))
                .andExpect(status().isForbidden)
            // Admin can delete it.
            mvc
                .perform(delete("/rest/api/4/components/$id/field-overrides/$overrideId").with(adminJwt()))
                .andExpect(status().isNoContent)
        }
    }

    // ---- helpers for the aspect/parent/people coverage below ----

    private fun JsonNode.baseScalar(aspect: String, field: String): String? =
        baseRow()[aspect]?.get(field)?.takeUnless { it.isNull }?.asText()

    /** POST a bob-owned component with a caller-supplied baseConfiguration (as admin). */
    private fun createBobRaw(baseConfig: String = """{"build":{"buildSystem":"MAVEN"}}"""): JsonNode {
        val body =
            """{"name":"crsB_${UUID.randomUUID().toString().take(8)}","componentOwner":"bob",""" +
                """"group":{"groupKey":"org.example.test","isFake":false},"baseConfiguration":$baseConfig}"""
        return objectMapper.readTree(
            mvc
                .perform(post("/rest/api/4/components").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString,
        )
    }

    private fun adminPost(bodyJson: String) =
        mvc.perform(post("/rest/api/4/components").with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(bodyJson))

    private fun editorPost(bodyJson: String) =
        mvc.perform(post("/rest/api/4/components").with(editorJwt()).contentType(MediaType.APPLICATION_JSON).content(bodyJson))

    // Finding 1+2 — full aspect-scalar set: hidden strip and adminOnly gating reach build/escrow.

    @Test
    @DisplayName("hidden aspect field (jira.minorVersionFormat): PATCH change silently STRIPPED, not applied, not 4xx")
    fun hiddenAspectField_stripOnPatch() {
        val mvf = "${'$'}major.${'$'}minor"
        val created = createBobRaw("""{"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"${uniqueProjectKey()}","minorVersionFormat":"$mvf"}}""")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("jira" to mapOf("minorVersionFormat" to mapOf("visibility" to "hidden")))) {
            patchRaw(adminJwt(), id, """{"version":$version,"baseConfiguration":{"jira":{"minorVersionFormat":"${'$'}changed"}}}""")
                .andExpect(status().is2xxSuccessful)
            assertEquals(mvf, getComponent(id).baseScalar("jira", "minorVersionFormat"), "hidden aspect must be stripped, not changed")
        }
    }

    @Test
    @DisplayName("hidden aspect field (jira.releaseVersionFormat): supplied on CREATE is silently STRIPPED (persists null)")
    fun hiddenAspectField_stripOnCreate() {
        withFieldConfig(mapOf("jira" to mapOf("releaseVersionFormat" to mapOf("visibility" to "hidden")))) {
            val created =
                createBobRaw("""{"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"${uniqueProjectKey()}","releaseVersionFormat":"${'$'}foo"}}""")
            assertEquals(null, created.baseScalar("jira", "releaseVersionFormat"), "hidden aspect must be stripped on create")
        }
    }

    @Test
    @DisplayName("adminOnly escrow.diskSpace: non-admin CHANGE → 403; ECHO unchanged → 2xx")
    fun adminOnlyEscrowDiskSpace_gated() {
        val created = createBobRaw("""{"build":{"buildSystem":"MAVEN"},"escrow":{"diskSpace":"10G"}}""")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("escrow" to mapOf("diskSpace" to mapOf("editable" to "adminOnly")))) {
            patchRaw(editorJwt(), id, """{"version":$version,"baseConfiguration":{"escrow":{"diskSpace":"20G"}}}""")
                .andExpect(status().isForbidden)
            assertEquals("10G", getComponent(id).baseScalar("escrow", "diskSpace"))
            patchRaw(editorJwt(), id, """{"version":$version,"baseConfiguration":{"escrow":{"diskSpace":"10G"}}}""")
                .andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("adminOnly build.buildFilePath: non-admin CHANGE → 403")
    fun adminOnlyBuildFilePath_reject() {
        val created = createBobRaw("""{"build":{"buildSystem":"MAVEN","buildFilePath":"pom.xml"}}""")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("build" to mapOf("buildFilePath" to mapOf("editable" to "adminOnly")))) {
            patchRaw(editorJwt(), id, """{"version":$version,"baseConfiguration":{"build":{"buildFilePath":"build.gradle"}}}""")
                .andExpect(status().isForbidden)
            assertEquals("pom.xml", getComponent(id).baseScalar("build", "buildFilePath"))
        }
    }

    // Finding 3 — override paths + hidden.

    @Test
    @DisplayName("OVERRIDE on hidden attribute: standalone create → 400 (masked as unknown, no leak)")
    fun override_hiddenAttribute_standalone400() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("visibility" to "hidden")))) {
            // Even admin gets a generic 400 — the standalone API never reveals hidden-ness.
            mvc
                .perform(
                    post("/rest/api/4/components/$id/field-overrides")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"overriddenAttribute":"jira.technical","versionRange":"[1.0,2.0)","value":true}"""),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    @DisplayName("OVERRIDE on hidden attribute: combined-save desired-set omitting it PRESERVES the row (strip, not delete)")
    fun override_hiddenAttribute_desiredSetPreserves() {
        val created = createOwnedByBob(technical = false)
        val id = created["id"].asText()

        // Create the override while the field is visible.
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"overriddenAttribute":"jira.technical","versionRange":"[1.0,2.0)","value":true}"""),
            ).andExpect(status().isCreated)

        withFieldConfig(mapOf("jira" to mapOf("technical" to mapOf("visibility" to "hidden")))) {
            val version = getComponent(id)["version"].asLong()
            // Desired-set = [] would delete all editable overrides; the hidden one must survive.
            patchRaw(adminJwt(), id, """{"version":$version,"fieldOverrides":[]}""")
                .andExpect(status().is2xxSuccessful)

            val overrides =
                objectMapper.readTree(
                    mvc.perform(get("/rest/api/4/components/$id/field-overrides").with(adminJwt()))
                        .andExpect(status().isOk).andReturn().response.contentAsString,
                )
            assertTrue(
                overrides.any { it["overriddenAttribute"].asText() == "jira.technical" },
                "hidden-attribute override must be preserved, not deleted by the desired-set",
            )
        }
    }

    // Finding 4 — create people-list gating.

    @Test
    @DisplayName("CREATE people: non-admin supplying an adminOnly releaseManager → 403; omitting → 2xx")
    fun create_people_gated() {
        withFieldConfig(mapOf("component" to mapOf("releaseManager" to mapOf("editable" to "adminOnly")))) {
            editorPost(
                """{"name":"crsB_ppl_${UUID.randomUUID().toString().take(8)}","componentOwner":"bob","releaseManager":["bob"],""" +
                    """"group":{"groupKey":"org.example.test","isFake":false},"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            ).andExpect(status().isForbidden)

            editorPost(
                """{"name":"crsB_ppl_${UUID.randomUUID().toString().take(8)}","componentOwner":"bob",""" +
                    """"group":{"groupKey":"org.example.test","isFake":false},"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
            ).andExpect(status().isCreated)
        }
    }

    // Finding 5 — parentComponentName / canBeParent gating.

    @Test
    @DisplayName("adminOnly canBeParent: non-admin flipping → 403; echoing unchanged → 2xx")
    fun adminOnlyCanBeParent_gated() {
        val created = createOwnedByBob() // canBeParent defaults to false
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("component" to mapOf("canBeParent" to mapOf("editable" to "adminOnly")))) {
            patchRaw(editorJwt(), id, """{"version":$version,"canBeParent":true}""").andExpect(status().isForbidden)
            patchRaw(editorJwt(), id, """{"version":$version,"canBeParent":false}""").andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("adminOnly parentComponentName: non-admin setting a parent → 403")
    fun adminOnlyParentComponentName_reject() {
        val parentKey = "crsB_parent_${UUID.randomUUID().toString().take(8)}"
        adminPost(
            """{"name":"$parentKey","componentOwner":"owner1","canBeParent":true,""" +
                """"group":{"groupKey":"org.example.test","isFake":false},"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
        ).andExpect(status().isCreated)

        val child = createOwnedByBob()
        val id = child["id"].asText()
        val version = child["version"].asLong()

        withFieldConfig(mapOf("component" to mapOf("parentComponentName" to mapOf("editable" to "adminOnly")))) {
            patchRaw(editorJwt(), id, """{"version":$version,"parentComponentName":"$parentKey"}""")
                .andExpect(status().isForbidden)
        }
    }

    // Hidden strip on CREATE for component-level fields (symmetry with PATCH).

    @Test
    @DisplayName("CREATE: hidden component.canBeParent supplied true → silently stripped to false")
    fun create_hiddenCanBeParent_stripped() {
        withFieldConfig(mapOf("component" to mapOf("canBeParent" to mapOf("visibility" to "hidden")))) {
            val created =
                objectMapper.readTree(
                    adminPost(
                        """{"name":"crsB_cbp_${UUID.randomUUID().toString().take(8)}","componentOwner":"owner1","canBeParent":true,""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ).andExpect(status().isCreated).andReturn().response.contentAsString,
                )
            assertEquals(false, created["canBeParent"].asBoolean(), "hidden canBeParent must be stripped to its default")
        }
    }

    @Test
    @DisplayName("CREATE: hidden component.releaseManager supplied → silently stripped (empty)")
    fun create_hiddenReleaseManager_stripped() {
        withFieldConfig(mapOf("component" to mapOf("releaseManager" to mapOf("visibility" to "hidden")))) {
            val created =
                objectMapper.readTree(
                    adminPost(
                        """{"name":"crsB_rm_${UUID.randomUUID().toString().take(8)}","componentOwner":"owner1","releaseManager":["bob"],""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ).andExpect(status().isCreated).andReturn().response.contentAsString,
                )
            assertTrue(created["releaseManager"].isEmpty, "hidden releaseManager must be stripped (not persisted)")
        }
    }

    @Test
    @DisplayName("hidden displayName: explicit+external CREATE with a stripped displayName does NOT 400 on required")
    fun hiddenDisplayName_explicitExternal_requiredGuard() {
        withFieldConfig(mapOf("component" to mapOf("displayName" to mapOf("visibility" to "hidden")))) {
            val g = "org.example.test.${UUID.randomUUID().toString().take(8)}"
            val resp =
                adminPost(
                    """{"name":"crsB_ee_${UUID.randomUUID().toString().take(8)}","componentOwner":"owner1",""" +
                        """"releaseManager":["rm1"],"securityChampion":["sc1"],""" +
                        """"displayName":"Should Be Stripped","distributionExplicit":true,"distributionExternal":true,""" +
                        """"group":{"groupKey":"org.example.test","isFake":false},""" +
                        """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                        """"mavenArtifacts":[{"groupPattern":"$g","artifactPattern":"art"}]}}""",
                ).andReturn().response
            assertEquals(201, resp.status, "explicit+external create must succeed; body=${resp.contentAsString}")
            val created = objectMapper.readTree(resp.contentAsString)
            // displayName was stripped (hidden) → persisted empty/null; the required rule was skipped.
            assertEquals("", created["displayName"].asText(""), "hidden displayName must be stripped, requiredness skipped")
        }
    }

    @Test
    @DisplayName("hidden productType: an invalid value on PATCH is stripped, not validated → 2xx (never 4xx)")
    fun hiddenProductType_badValueStrippedNotRejected() {
        val created = createOwnedByBob()
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("component" to mapOf("productType" to mapOf("visibility" to "hidden")))) {
            // "NOT_A_TYPE" would 400 via validateProductType if not skipped for the hidden field.
            patchRaw(adminJwt(), id, """{"version":$version,"productType":"NOT_A_TYPE"}""")
                .andExpect(status().is2xxSuccessful)
        }
    }

    @Test
    @DisplayName("hidden escrow.generation: a bad value is stripped, not validated → 2xx (never 4xx)")
    fun hiddenEscrowGeneration_badValueStrippedNotRejected() {
        val created = createBobRaw("""{"build":{"buildSystem":"MAVEN"},"escrow":{"generation":"AUTO"}}""")
        val id = created["id"].asText()
        val version = created["version"].asLong()

        withFieldConfig(mapOf("escrow" to mapOf("generation" to mapOf("visibility" to "hidden")))) {
            // "NOTAMODE" would be a 400 if validated; hidden must strip it before validation.
            patchRaw(adminJwt(), id, """{"version":$version,"baseConfiguration":{"escrow":{"generation":"NOTAMODE"}}}""")
                .andExpect(status().is2xxSuccessful)
            assertEquals("AUTO", getComponent(id).baseScalar("escrow", "generation"))
        }
    }
}
