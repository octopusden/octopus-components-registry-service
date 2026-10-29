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
import org.octopusden.octopus.components.registry.server.support.editorJwt
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-104 — the component-level `autoRelease` flag: v4 create (default false / true), update,
 * detail + summary read, `?autoRelease=` list filter, and field-config enforcement
 * (`hidden` strips the value, `adminOnly` rejects a non-admin change). `ft-db` profile; every
 * test seeds its own uniquely-named components.
 */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class AutoReleaseFlagV4Test {
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
        val testResourcesPath = Paths.get(AutoReleaseFlagV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun name() = "auto-release-${UUID.randomUUID().toString().take(8)}"

    private fun seedFieldConfig(autoRelease: Map<String, String>?) {
        val entity = registryConfigRepository.findById("field-config").orElse(RegistryConfigEntity(key = "field-config"))
        entity.value = if (autoRelease == null) emptyMap() else mapOf("component" to mapOf("autoRelease" to autoRelease))
        registryConfigRepository.save(entity)
    }

    private fun postCreate(
        body: String,
        jwt: RequestPostProcessor = adminJwt(),
        owner: String = "owner1",
    ): ResultActions =
        mvc.perform(
            post("/rest/api/4/components")
                .with(jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"componentOwner":"$owner","baseConfiguration":{"build":{"buildSystem":"MAVEN"}},$body}"""),
        )

    private fun create(body: String): JsonNode =
        objectMapper.readTree(
            postCreate(body)
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString,
        )

    private fun patchComponent(
        detail: JsonNode,
        body: String,
        jwt: RequestPostProcessor = adminJwt(),
    ): ResultActions =
        mvc.perform(
            patch("/rest/api/4/components/${detail["id"].asText()}")
                .with(jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"version":${detail["version"].asLong()},$body}"""),
        )

    private fun ResultActions.body(expected: org.springframework.test.web.servlet.ResultMatcher): JsonNode =
        objectMapper.readTree(andExpect(expected).andReturn().response.contentAsString)

    private fun getJson(url: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(get(url).with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun listNames(query: String): List<String> =
        getJson("/rest/api/4/components?size=100&$query")["content"].map { it["name"].asText() }

    @Test
    @DisplayName("SYS-104: create defaults autoRelease to false; true is stored and read back in detail and summary")
    fun `SYS-104 create default false and true`() {
        seedFieldConfig(null)
        val plain = name()
        val auto = name()
        assertFalse(create(""""name":"$plain"""")["autoRelease"].asBoolean())
        assertTrue(create(""""name":"$auto","autoRelease":true""")["autoRelease"].asBoolean())

        assertFalse(getJson("/rest/api/4/components/$plain")["autoRelease"].asBoolean())
        assertTrue(getJson("/rest/api/4/components/$auto")["autoRelease"].asBoolean())
        assertTrue(getJson("/rest/api/4/components?search=$auto")["content"].single()["autoRelease"].asBoolean())
        assertFalse(getJson("/rest/api/4/components?search=$plain")["content"].single()["autoRelease"].asBoolean())
    }

    @Test
    @DisplayName("SYS-104: PATCH sets and clears autoRelease; omitting it leaves it unchanged")
    fun `SYS-104 update`() {
        seedFieldConfig(null)
        val key = name()
        var detail = create(""""name":"$key"""")

        detail = patchComponent(detail, """"autoRelease":true""").body(status().isOk)
        assertTrue(detail["autoRelease"].asBoolean())

        detail = patchComponent(detail, """"displayName":"$key-dn"""").body(status().isOk)
        assertTrue(detail["autoRelease"].asBoolean(), "an omitted autoRelease is untouched")

        detail = patchComponent(detail, """"autoRelease":false""").body(status().isOk)
        assertFalse(detail["autoRelease"].asBoolean())
        assertFalse(getJson("/rest/api/4/components/$key")["autoRelease"].asBoolean())
    }

    @Test
    @DisplayName("SYS-104: ?autoRelease= filters exactly; without it both are listed")
    fun `SYS-104 filter`() {
        seedFieldConfig(null)
        val plain = name()
        val auto = name()
        create(""""name":"$plain"""")
        create(""""name":"$auto","autoRelease":true""")

        val onlyAuto = listNames("autoRelease=true")
        assertTrue(auto in onlyAuto)
        assertFalse(plain in onlyAuto)
        val onlyPlain = listNames("autoRelease=false")
        assertTrue(plain in onlyPlain)
        assertFalse(auto in onlyPlain)
        val all = listNames("")
        assertTrue(plain in all && auto in all)
    }

    @Test
    @DisplayName("SYS-104: field-config hidden strips autoRelease on create and update")
    fun `SYS-104 hidden field is stripped`() {
        seedFieldConfig(mapOf("visibility" to "hidden"))
        try {
            val key = name()
            val detail = create(""""name":"$key","autoRelease":true""")
            assertFalse(detail["autoRelease"].asBoolean(), "create ignores a hidden field")
            patchComponent(detail, """"autoRelease":true""").andExpect(status().isOk)
            assertFalse(getJson("/rest/api/4/components/$key")["autoRelease"].asBoolean(), "update ignores a hidden field")
        } finally {
            seedFieldConfig(null)
        }
    }

    @Test
    @DisplayName("SYS-104: field-config adminOnly rejects a non-admin change, allows an admin and an echo")
    fun `SYS-104 adminOnly field is enforced`() {
        seedFieldConfig(mapOf("editable" to "adminOnly"))
        try {
            val key = name()
            postCreate(""""name":"${name()}","autoRelease":true""", editorJwt()).andExpect(status().isForbidden)
            postCreate(""""name":"${name()}"""", editorJwt()).andExpect(status().isCreated)
            // The editor "bob" owns the component, so it passes the per-component edit gate.
            val detail = postCreate(""""name":"$key"""", owner = "bob").body(status().isCreated)
            patchComponent(detail, """"autoRelease":true""", editorJwt()).andExpect(status().isForbidden)
            patchComponent(detail, """"autoRelease":false""", editorJwt()).andExpect(status().isOk)
            patchComponent(getJson("/rest/api/4/components/$key"), """"autoRelease":true""").andExpect(status().isOk)
            assertEquals(true, getJson("/rest/api/4/components/$key")["autoRelease"].asBoolean())
        } finally {
            seedFieldConfig(null)
        }
    }
}
