package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-099 — the component-level `testComponent` flag: v4 create/update/read and list filter, the
 * legacy v2/v3 read surface, the no-real-product-references-test-data rule (400), the
 * `test-component` label warning, and the health-statistics exclusion. `ft-db` profile; every
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
class TestComponentFlagV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(TestComponentFlagV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun postCreate(body: String): ResultActions =
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"componentOwner":"owner1","baseConfiguration":{"build":{"buildSystem":"MAVEN"}},$body}"""),
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
    ): ResultActions =
        mvc.perform(
            patch("/rest/api/4/components/${detail["id"].asText()}")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"version":${detail["version"].asLong()},$body}"""),
        )

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
    @DisplayName("SYS-099: testComponent round-trips through v4 detail, summary, v2 and v3; others read false")
    fun `SYS-099 testComponent is returned by v4 v2 and v3`() {
        val test = name("sys099-test")
        val real = name("sys099-real")
        assertTrue(create(""""name":"$test","testComponent":true""")["testComponent"].asBoolean())
        assertFalse(create(""""name":"$real"""")["testComponent"].asBoolean())

        assertTrue(getJson("/rest/api/4/components/$test")["testComponent"].asBoolean())
        val summary = getJson("/rest/api/4/components?search=$test")["content"].single()
        assertTrue(summary["testComponent"].asBoolean())

        // Legacy surface: always present, true or false.
        assertTrue(getJson("/rest/api/2/components/$test")["testComponent"].asBoolean())
        assertFalse(getJson("/rest/api/2/components/$real")["testComponent"].asBoolean())
        val v3 = getJson("/rest/api/3/components").associateBy { it["component"]["id"].asText() }
        assertTrue(v3.getValue(test)["component"]["testComponent"].asBoolean())
        assertFalse(v3.getValue(real)["component"]["testComponent"].asBoolean())

        // The as-code view stays parseable by the legacy DSL, which has no such key.
        mvc
            .perform(get("/rest/api/4/components/$test/as-code").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect { assertFalse(it.response.contentAsString.contains("testComponent")) }
    }

    @Test
    @DisplayName("SYS-099: find-by-artifact results carry the component's testComponent flag")
    fun `SYS-099 find-by-artifact carries testComponent`() {
        val test = name("sys099-fba-test")
        val real = name("sys099-fba-real")
        val group = "org.octopusden.octopus.$test"

        fun ownership(artifact: String) = """"artifactIds":[{"groupPattern":"$group","mode":"EXPLICIT","artifactTokens":["$artifact"]}]"""
        create(""""name":"$test","testComponent":true,${ownership("t-lib")}""")
        create(""""name":"$real",${ownership("r-lib")}""")

        fun post(
            url: String,
            body: String,
        ): JsonNode =
            objectMapper.readTree(
                mvc
                    .perform(post(url).with(adminJwt()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )

        fun dep(artifact: String) = """{"group":"$group","name":"$artifact","version":"1.0"}"""

        val single = post("/rest/api/2/components/find-by-artifact", dep("t-lib"))
        assertEquals(test, single["id"].asText())
        assertTrue(single["testComponent"].asBoolean())

        val v2 = post("/rest/api/2/components/findByArtifacts", "[${dep("t-lib")},${dep("r-lib")}]")
            .associate { it["id"].asText() to it["testComponent"].asBoolean() }
        assertEquals(mapOf(test to true, real to false), v2)

        val v3 = post("/rest/api/3/components/find-by-artifacts", "[${dep("t-lib")},${dep("r-lib")}]")["artifactComponents"]
            .associate { it["component"]["id"].asText() to it["component"]["testComponent"].asBoolean() }
        assertEquals(mapOf(test to true, real to false), v3)
    }

    @Test
    @DisplayName("SYS-099: the component's own editor can set testComponent without ARCHIVE_COMPONENTS")
    fun `SYS-099 editor without archive permission can flag`() {
        val detail = create(""""name":"${name("sys099-editor")}"""")
        mvc
            .perform(
                patch("/rest/api/4/components/${detail["id"].asText()}")
                    .with(editorJwt("owner1"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${detail["version"].asLong()},"testComponent":true}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.testComponent").value(true))
    }

    @Test
    @DisplayName("SYS-099: list includes test components unless filtered with testComponent=false")
    fun `SYS-099 list filter on testComponent`() {
        val prefix = name("sys099-list")
        create(""""name":"$prefix-t","testComponent":true""")
        create(""""name":"$prefix-r"""")

        assertEquals(setOf("$prefix-t", "$prefix-r"), listNames("search=$prefix").toSet())
        assertEquals(listOf("$prefix-r"), listNames("search=$prefix&testComponent=false"))
        assertEquals(listOf("$prefix-t"), listNames("search=$prefix&testComponent=true"))
    }

    @Test
    @DisplayName("SYS-099: a real component may not use a test component as its parent (400 naming both)")
    fun `SYS-099 real component referencing test parent is rejected`() {
        val parent = name("sys099-tparent")
        val child = name("sys099-child")
        create(""""name":"$parent","testComponent":true,"canBeParent":true""")

        postCreate(""""name":"$child","parentComponentName":"$parent"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(containsString("'$child'"), containsString("'$parent'"))))
        // A test component may sit under a test parent.
        create(""""name":"$child","parentComponentName":"$parent","testComponent":true""")
    }

    @Test
    @DisplayName("SYS-099: a real component may not use a test component as its doc component (400 naming both)")
    fun `SYS-099 real component referencing test doc component is rejected`() {
        val doc = name("sys099-tdoc")
        val real = name("sys099-docuser")
        create(""""name":"$doc","testComponent":true""")
        val detail = create(""""name":"$real"""")

        patchComponent(detail, """"docs":[{"docComponentKey":"$doc"}]""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(containsString("'$real'"), containsString("'$doc'"))))
    }

    @Test
    @DisplayName("SYS-099: flagging a component that real components reference is rejected (400 naming both)")
    fun `SYS-099 flagging a referenced component is rejected`() {
        val parent = name("sys099-parent")
        val child = name("sys099-realchild")
        val parentDetail = create(""""name":"$parent","canBeParent":true""")
        create(""""name":"$child","parentComponentName":"$parent"""")

        patchComponent(parentDetail, """"testComponent":true""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(containsString("'$parent'"), containsString("'$child'"))))
    }

    @Test
    @DisplayName("SYS-099: the test-component label without the flag yields a warning, not an error")
    fun `SYS-099 label without flag warns`() {
        val labelled = create(""""name":"${name("sys099-label")}","labels":["test-component"]""")
        assertTrue(labelled["warnings"].any { it.asText().contains("testComponent") }) { "warnings: ${labelled["warnings"]}" }

        patchComponent(labelled, """"testComponent":true""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.warnings").value(not(hasItem(containsString("testComponent")))))
    }

    @Test
    @DisplayName("SYS-099: health statistics do not count test components")
    fun `SYS-099 health statistics exclude test components`() {
        val before = getJson("/rest/api/4/health/statistics")
        create(""""name":"${name("sys099-stat-r")}"""")
        create(""""name":"${name("sys099-stat-t")}","testComponent":true""")

        val after = getJson("/rest/api/4/health/statistics")
        assertEquals(before["totalComponents"].asLong() + 1, after["totalComponents"].asLong())
        assertEquals(before["activeComponents"].asLong() + 1, after["activeComponents"].asLong())
    }
}
