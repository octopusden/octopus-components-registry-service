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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when` as whenMock
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.common.dto.ManagerDTO
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * End-to-end (HTTP → @PreAuthorize → service → H2) coverage for the per-component
 * edit gate (ADR-004 Phase 2): only a component's `componentOwner`,
 * `releaseManager`, or `securityChampion` — or an admin holding
 * `EDIT_ANY_COMPONENT` — may PATCH it or mutate its field-overrides. Also pins
 * the per-user `canEdit` flag on the GET / create / update detail responses.
 *
 * `adminJwt()` = alice/ROLE_ADMIN (has EDIT_ANY_COMPONENT), `editorJwt(name)` =
 * name/ROLE_COMPONENTS_REGISTRY_EDITOR, `viewerJwt(name)` =
 * name/ROLE_COMPONENTS_REGISTRY_VIEWER (ACCESS_COMPONENTS only, no CREATE_COMPONENTS).
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
class ComponentOwnershipEditSecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var employeeServiceClient: EmployeeServiceClient

    @BeforeEach
    fun setupEmployeeMock() {
        // Default: no manager — existing tests are unaffected.
        whenMock(employeeServiceClient.getManager(anyString())).thenReturn(ManagerDTO(null))
    }

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        val testResourcesPath =
            Paths.get(ComponentOwnershipEditSecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    /** Create a component (admin by default — create is gated on raw CREATE_COMPONENTS, not ownership). */
    private fun create(
        name: String,
        owner: String? = null,
        releaseManager: List<String> = emptyList(),
        securityChampion: List<String> = emptyList(),
        jwt: RequestPostProcessor = adminJwt(),
    ): JsonNode {
        val fields =
            buildMap<String, Any?> {
                put("name", name)
                put("baseConfiguration", mapOf("build" to mapOf("buildSystem" to "MAVEN")))
                if (owner != null) put("componentOwner", owner)
                put("releaseManager", releaseManager)
                put("securityChampion", securityChampion)
            }
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fields)),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun performPatch(
        id: String,
        version: Long,
        fields: Map<String, Any?>,
        jwt: RequestPostProcessor,
    ): ResultActions {
        val body = objectMapper.writeValueAsString(mapOf("version" to version) + fields)
        return mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
    }

    private fun performGet(
        id: String,
        jwt: RequestPostProcessor,
    ): ResultActions = mvc.perform(get("/rest/api/4/components/$id").with(jwt))

    private fun performFieldOverridePost(
        id: String,
        jwt: RequestPostProcessor,
    ): ResultActions {
        val body =
            objectMapper.writeValueAsString(
                mapOf(
                    "overriddenAttribute" to "build.javaVersion",
                    "versionRange" to "[1.0,2.0)",
                    "value" to "17",
                ),
            )
        return mvc.perform(
            post("/rest/api/4/components/$id/field-overrides")
                .with(jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
    }

    private fun JsonNode.id(): String = this["id"].asText()

    private fun JsonNode.version(): Long = this["version"].asLong()

    // Fail loudly if the flag is absent so a dropped `withCanEdit` can't make an
    // `assertFalse(... .canEdit())` pass vacuously (JsonNode.asBoolean() → false on missing).
    private fun JsonNode.canEdit(): Boolean =
        (this["canEdit"] ?: error("canEdit field missing from response")).asBoolean()

    // Unique per call: displayName is now UNIQUE, so a shared literal would 400 on the
    // second edit. The value is never asserted — only used as a non-trivial PATCH body.
    private fun bumpDisplayName() = mapOf("displayName" to "edited-${UUID.randomUUID()}")

    // --- ownership grants edit -------------------------------------------------

    @Test
    @DisplayName("componentOwner can PATCH their component")
    fun `owner can patch`() {
        val c = create(uniqueName("owner"), owner = "bob")
        performPatch(c.id(), c.version(), bumpDisplayName(), viewerJwt("bob"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("releaseManager can PATCH the component")
    fun `release manager can patch`() {
        val c = create(uniqueName("rm"), owner = "alice-owner", releaseManager = listOf("dave"))
        performPatch(c.id(), c.version(), bumpDisplayName(), viewerJwt("dave"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("securityChampion can PATCH the component")
    fun `security champion can patch`() {
        val c = create(uniqueName("sc"), owner = "alice-owner", securityChampion = listOf("erin"))
        performPatch(c.id(), c.version(), bumpDisplayName(), viewerJwt("erin"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("username match is case-insensitive (stored 'Bob' vs token 'bob')")
    fun `case insensitive match`() {
        val c = create(uniqueName("case"), owner = "Bob")
        performPatch(c.id(), c.version(), bumpDisplayName(), viewerJwt("bob"))
            .andExpect(status().isOk)
    }

    // --- ownership denies edit -------------------------------------------------

    @Test
    @DisplayName("editor who is not owner/RM/SC gets 403 on PATCH")
    fun `unrelated editor forbidden`() {
        val c = create(uniqueName("unrelated"), owner = "bob")
        performPatch(c.id(), c.version(), bumpDisplayName(), editorJwt("frank"))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("ACCESS-only user may PATCH when they are the owner")
    fun `viewer owner allowed`() {
        // carol is both the stored owner AND the viewer principal. The component-scoped
        // edit gate depends on assignment, not on the coarse CREATE_COMPONENTS role.
        val c = create(uniqueName("viewerowner"), owner = "carol")
        performPatch(c.id(), c.version(), bumpDisplayName(), viewerJwt())
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("PATCH of a non-existent id is 403 (gate runs before the controller)")
    fun `not found is forbidden not 404`() {
        performPatch(UUID.randomUUID().toString(), 0L, bumpDisplayName(), editorJwt("frank"))
            .andExpect(status().isForbidden)
    }

    // --- admin bypass & empty-roles --------------------------------------------

    @Test
    @DisplayName("admin (EDIT_ANY_COMPONENT) bypasses ownership")
    fun `admin bypass`() {
        val c = create(uniqueName("adminbypass"), owner = "bob")
        performPatch(c.id(), c.version(), bumpDisplayName(), adminJwt())
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("owner-less legacy component can only be repaired by admin")
    fun `empty roles admin only`() {
        val c = create(uniqueName("empty"), owner = "legacy-owner")
        jdbcTemplate.update("UPDATE components SET component_owner = NULL WHERE id = ?", UUID.fromString(c.id()))
        performPatch(c.id(), c.version(), bumpDisplayName(), editorJwt("frank"))
            .andExpect(status().isForbidden)
        performPatch(c.id(), c.version(), mapOf("componentOwner" to "repaired-owner"), adminJwt())
            .andExpect(status().isOk)
    }

    // --- field-override CRUD shares the same gate ------------------------------

    @Test
    @DisplayName("field-override create: owner 201, unrelated editor 403")
    fun `field override gated by ownership`() {
        val c = create(uniqueName("fo"), owner = "bob")
        performFieldOverridePost(c.id(), editorJwt("frank"))
            .andExpect(status().isForbidden)
        performFieldOverridePost(c.id(), viewerJwt("bob"))
            .andExpect(status().isCreated)
    }

    // --- canEdit affordance on the detail response -----------------------------

    @Test
    @DisplayName("GET canEdit reflects the caller: owner/admin true, unrelated false")
    fun `canEdit on get`() {
        val c = create(uniqueName("canedit_get"), owner = "bob")
        val id = c.id()
        assertTrue(objectMapper.readTree(getBody(id, viewerJwt("bob"))).canEdit(), "owner with ACCESS only")
        assertTrue(objectMapper.readTree(getBody(id, adminJwt())).canEdit(), "admin")
        assertFalse(objectMapper.readTree(getBody(id, editorJwt("frank"))).canEdit(), "unrelated editor")
        assertFalse(objectMapper.readTree(getBody(id, viewerJwt())).canEdit(), "unrelated viewer")
    }

    @Test
    @DisplayName("PATCH response carries a fresh canEdit: owner who removes themselves gets canEdit=false")
    fun `canEdit on patch reflects self-removal`() {
        val c = create(uniqueName("canedit_patch"), owner = "bob")
        // bob is allowed (current stored owner) — but he hands ownership to zoe.
        val body =
            performPatch(c.id(), c.version(), mapOf("componentOwner" to "zoe"), editorJwt("bob"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        assertFalse(objectMapper.readTree(body).canEdit(), "bob is no longer owner → canEdit=false on the response")
    }

    @Test
    @DisplayName("create response canEdit reflects the creator (editor self-owner true, other-owner false)")
    fun `canEdit on create`() {
        val self = create(uniqueName("canedit_self"), owner = "bob", jwt = editorJwt("bob"))
        assertTrue(self.canEdit(), "editor created it owning it")
        val other = create(uniqueName("canedit_other"), owner = "zoe", jwt = editorJwt("bob"))
        assertFalse(other.canEdit(), "editor created it but is not owner")
    }

    @Test
    @DisplayName("field-override update is ownership-gated (unrelated editor 403, owner 200)")
    fun `field override update gated`() {
        val c = create(uniqueName("fo_upd"), owner = "bob")
        val overrideId = createOverrideAsOwner(c.id())
        val patchBody = objectMapper.writeValueAsString(mapOf("value" to "21"))
        mvc.perform(
            patch("/rest/api/4/components/${c.id()}/field-overrides/$overrideId")
                .with(editorJwt("frank"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isForbidden)
        mvc.perform(
            patch("/rest/api/4/components/${c.id()}/field-overrides/$overrideId")
                .with(viewerJwt("bob"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody),
        ).andExpect(status().isOk)
    }

    @Test
    @DisplayName("field-override delete is also ownership-gated (unrelated editor 403)")
    fun `field override delete gated`() {
        val c = create(uniqueName("fo_del"), owner = "bob")
        val overrideId = createOverrideAsOwner(c.id())
        mvc.perform(delete("/rest/api/4/components/${c.id()}/field-overrides/$overrideId").with(editorJwt("frank")))
            .andExpect(status().isForbidden)
        mvc.perform(delete("/rest/api/4/components/${c.id()}/field-overrides/$overrideId").with(viewerJwt("bob")))
            .andExpect(status().isNoContent)
    }

    private fun createOverrideAsOwner(componentId: String): String =
        objectMapper
            .readTree(
                performFieldOverridePost(componentId, viewerJwt("bob"))
                    .andExpect(status().isCreated)
                    .andReturn().response.contentAsString,
            )["id"].asText()

    private fun getBody(
        id: String,
        jwt: RequestPostProcessor,
    ): String =
        performGet(id, jwt)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

    // --- OCTOPUS-2191: manager of componentOwner ----------------------------------

    @Test
    @DisplayName("manager of componentOwner can PATCH the component (OCTOPUS-2191)")
    fun `owner manager can patch`() {
        val c = create(uniqueName("mgr_allow"), owner = "bob")
        whenMock(employeeServiceClient.getManager("bob")).thenReturn(ManagerDTO("mgr-user"))
        performPatch(c.id(), c.version(), bumpDisplayName(), editorJwt("mgr-user"))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("non-manager of componentOwner gets 403 on PATCH")
    fun `owner non-manager forbidden`() {
        val c = create(uniqueName("mgr_deny"), owner = "bob")
        whenMock(employeeServiceClient.getManager("bob")).thenReturn(ManagerDTO("other-mgr"))
        performPatch(c.id(), c.version(), bumpDisplayName(), editorJwt("frank"))
            .andExpect(status().isForbidden)
    }
}
