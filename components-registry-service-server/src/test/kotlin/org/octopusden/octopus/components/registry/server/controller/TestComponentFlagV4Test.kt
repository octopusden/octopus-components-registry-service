package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.Component
import org.octopusden.octopus.components.registry.core.dto.ComponentV3
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.ResolvableType
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
import org.springframework.web.method.ControllerAdviceBean
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
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

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var testComponentFlagAdvice: TestComponentFlagAdvice

    init {
        val testResourcesPath =
            Paths.get(TestComponentFlagV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    /** Sets the flag behind the API's back — the state V11 or a past rule can leave behind. */
    private fun flagInDb(key: String) {
        val entity = componentRepository.findByComponentKey(key)!!
        entity.testComponent = true
        componentRepository.save(entity)
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
        val test = name("test-sys099")
        val real = name("sys099-real")
        assertTrue(create(""""name":"$test","testComponent":true""")["testComponent"].asBoolean())
        assertFalse(create(""""name":"$real"""")["testComponent"].asBoolean())

        assertTrue(getJson("/rest/api/4/components/$test")["testComponent"].asBoolean())
        val summary = getJson("/rest/api/4/components?search=$test")["content"].single()
        assertTrue(summary["testComponent"].asBoolean())

        // Legacy surface: always present, true or false, on every component-returning endpoint.
        listOf("1", "2").forEach { v ->
            assertTrue(getJson("/rest/api/$v/components/$test")["testComponent"].asBoolean(), "v$v detail")
            assertFalse(getJson("/rest/api/$v/components/$real")["testComponent"].asBoolean(), "v$v detail")
            val list = getJson("/rest/api/$v/components")["components"].associateBy { it["id"].asText() }
            assertTrue(list.getValue(test)["testComponent"].asBoolean(), "v$v list")
            assertFalse(list.getValue(real)["testComponent"].asBoolean(), "v$v list")
        }
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
    @DisplayName("SYS-099: every v1-v3 handler that returns a Component DTO is in the flag advice's scope")
    fun `SYS-099 every v1-v3 component endpoint is covered by the flag advice`() {
        val advice = ControllerAdviceBean(testComponentFlagAdvice)
        val covered =
            handlerMapping.handlerMethods.filter { (info, handler) ->
                info.patternValues.any { V1_V3.containsMatchIn(it) } &&
                    carriesComponent(ResolvableType.forMethodReturnType(handler.method, handler.beanType))
            }
        // List, detail, DetailedComponent, find-by-artifact (v2 x2, v3), v3 list at least.
        assertTrue(covered.size >= 8) { "expected the v1-v3 component endpoints, found ${covered.keys}" }
        covered.forEach { (info, handler) ->
            assertTrue(advice.isApplicableToBeanType(handler.beanType)) { "$info (${handler.beanType.simpleName}) escapes the advice" }
        }
    }

    private fun carriesComponent(type: ResolvableType): Boolean {
        val raw = type.resolve() ?: return false
        return COMPONENT_CARRIERS.any { it.isAssignableFrom(raw) } || type.generics.any(::carriesComponent)
    }

    @Test
    @DisplayName("SYS-099: find-by-artifact results carry the component's testComponent flag")
    fun `SYS-099 find-by-artifact carries testComponent`() {
        val test = name("test-sys099-fba")
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
        val detail = create(""""name":"${name("test-sys099-editor")}"""")
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
    @DisplayName("SYS-099: testComponent = true needs a key matching the configured name patterns")
    fun `SYS-099 flag requires a matching key`() {
        val real = name("sys099-nomatch")
        postCreate(""""name":"$real","testComponent":true""")
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath(
                    "$.errorMessage",
                ).value(allOf(startsWith("testComponent: "), containsString("'$real'"), containsString("^test-"))),
            )
        // cvelab- is the second default pattern.
        create(""""name":"${name("cvelab-sys099")}","testComponent":true""")

        val detail = create(""""name":"$real"""")
        patchComponent(detail, """"testComponent":true""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(startsWith("testComponent: "), containsString("'$real'"))))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
    }

    @Test
    @DisplayName("SYS-099: echoing testComponent=true on a flagged key that matches no pattern is accepted")
    fun `SYS-099 unchanged flag echo on a non-matching key is accepted`() {
        val legacy = name("sys099-legacy")
        create(""""name":"$legacy"""")
        flagInDb(legacy) // e.g. flagged before a pattern change
        val detail = getJson("/rest/api/4/components/$legacy")
        patchComponent(detail, """"testComponent":true,"displayName":"$legacy echo"""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.testComponent").value(true))
    }

    @Test
    @DisplayName("SYS-099: echoing testComponent=false and the same parent on a real child of a test parent is accepted")
    fun `SYS-099 unchanged echo on a grandfathered real child is accepted`() {
        val parent = name("test-sys099-gfparent")
        val child = name("sys099-gfchild")
        create(""""name":"$parent","canBeParent":true""")
        create(""""name":"$child","parentComponentName":"$parent"""")
        flagInDb(parent) // grandfathered: the parent became a test component outside the API
        val detail = getJson("/rest/api/4/components/$child")
        patchComponent(detail, """"testComponent":false,"parentComponentName":"$parent","displayName":"$child echo"""")
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("SYS-099: unflagging a test child of a test parent names the field that changed: testComponent")
    fun `SYS-099 unflagging a child of a test parent is rejected on testComponent`() {
        val parent = name("test-sys099-uparent")
        val child = name("test-sys099-uchild")
        create(""""name":"$parent","canBeParent":true,"testComponent":true""")
        val detail = create(""""name":"$child","parentComponentName":"$parent","testComponent":true""")
        patchComponent(detail, """"testComponent":false""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(startsWith("testComponent: "), containsString("'$parent'"))))
    }

    @Test
    @DisplayName("SYS-099: a PATCH checks only the reference it changes; untouched grandfathered references stay")
    fun `SYS-099 forward check covers only the changed reference`() {
        // Real B under a grandfathered test parent: changing only its docs (to a real doc) is fine.
        val testParent = name("test-sys099-gparent")
        val b = name("sys099-gb")
        val realDoc = name("sys099-greal-doc")
        create(""""name":"$testParent","canBeParent":true""")
        create(""""name":"$b","parentComponentName":"$testParent"""")
        create(""""name":"$realDoc"""")
        flagInDb(testParent)
        patchComponent(getJson("/rest/api/4/components/$b"), """"docs":[{"docComponentKey":"$realDoc"}]""")
            .andExpect(status().isOk)

        // Real C with a grandfathered test doc link: changing only its parent is fine.
        val oldTestDoc = name("test-sys099-gdoc")
        val realParent = name("sys099-grparent")
        val c = name("sys099-gc")
        create(""""name":"$oldTestDoc"""")
        create(""""name":"$realParent","canBeParent":true""")
        create(""""name":"$c","docs":[{"docComponentKey":"$oldTestDoc"}]""")
        flagInDb(oldTestDoc)
        patchComponent(getJson("/rest/api/4/components/$c"), """"parentComponentName":"$realParent"""")
            .andExpect(status().isOk)

        // Adding a test doc key is rejected on `docs:`, naming the new key — not the grandfathered one.
        val newTestDoc = name("test-sys099-gnewdoc")
        create(""""name":"$newTestDoc","testComponent":true""")
        patchComponent(
            getJson("/rest/api/4/components/$c"),
            """"docs":[{"docComponentKey":"$oldTestDoc"},{"docComponentKey":"$newTestDoc"}]""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(startsWith("docs: "), containsString("'$newTestDoc'"))))
    }

    @Test
    @DisplayName("SYS-099: renaming a test component out of the patterns is rejected; unflagging is always allowed")
    fun `SYS-099 rename of a flagged component must keep a matching key`() {
        val flagged = create(""""name":"${name("test-sys099-rename")}","testComponent":true""")
        val outside = name("sys099-renamed")
        patchComponent(flagged, """"name":"$outside"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(startsWith("name: "), containsString("'$outside'"))))

        val inside = name("test-sys099-renamed")
        val renamed = objectMapper.readTree(
            patchComponent(flagged, """"name":"$inside"""")
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
        // Unflagging together with a rename out of the patterns is fine: the rule binds only flagged components.
        patchComponent(renamed, """"name":"$outside","testComponent":false""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.testComponent").value(false))
    }

    @Test
    @DisplayName("SYS-099: GET /meta/test-component-name-patterns returns the configured patterns")
    fun `SYS-099 meta endpoint lists name patterns`() {
        val patterns = getJson("/rest/api/4/components/meta/test-component-name-patterns").map { it.asText() }
        assertEquals(listOf("^test-", "^cvelab-"), patterns)
    }

    @Test
    @DisplayName("SYS-099: list includes test components unless filtered with testComponent=false")
    fun `SYS-099 list filter on testComponent`() {
        val prefix = name("test-sys099-list")
        create(""""name":"$prefix-t","testComponent":true""")
        create(""""name":"$prefix-r"""")

        assertEquals(setOf("$prefix-t", "$prefix-r"), listNames("search=$prefix").toSet())
        assertEquals(listOf("$prefix-r"), listNames("search=$prefix&testComponent=false"))
        assertEquals(listOf("$prefix-t"), listNames("search=$prefix&testComponent=true"))
    }

    @Test
    @DisplayName("SYS-099: a real component may not use a test component as its parent (400 naming both)")
    fun `SYS-099 real component referencing test parent is rejected`() {
        val parent = name("test-sys099-parent")
        val child = name("test-sys099-child")
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
        val doc = name("test-sys099-doc")
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
        val parent = name("test-sys099-refparent")
        val child = name("sys099-realchild")
        val parentDetail = create(""""name":"$parent","canBeParent":true""")
        create(""""name":"$child","parentComponentName":"$parent"""")

        patchComponent(parentDetail, """"testComponent":true""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(allOf(containsString("'$parent'"), containsString("'$child'"))))
    }

    @Test
    @DisplayName("SYS-099: renaming a test component onto a key a real component's soft doc link still names is rejected")
    fun `SYS-099 rename of a test component onto a dangling doc link is rejected`() {
        val docKey = name("test-sys099-dockey")
        val doc = create(""""name":"$docKey"""")
        val real = name("sys099-docholder")
        create(""""name":"$real","docs":[{"docComponentKey":"$docKey"}]""")
        // The doc target moves away; the real component's soft link stays on the old key.
        patchComponent(doc, """"name":"${name("test-sys099-moved")}"""").andExpect(status().isOk)

        val test = create(""""name":"${name("test-sys099-renamer")}","testComponent":true""")
        patchComponent(test, """"name":"$docKey"""")
            .andExpect(status().isBadRequest)
            .andExpect(
                jsonPath("$.errorMessage").value(allOf(startsWith("name: "), containsString("'$docKey'"), containsString("'$real'"))),
            )
    }

    @Test
    @DisplayName("SYS-099: the test-component label without the flag yields a warning, not an error")
    fun `SYS-099 label without flag warns`() {
        val labelled = create(""""name":"${name("test-sys099-label")}","labels":["test-component"]""")
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
        create(""""name":"${name("test-sys099-stat")}","testComponent":true""")

        val after = getJson("/rest/api/4/health/statistics")
        assertEquals(before["totalComponents"].asLong() + 1, after["totalComponents"].asLong())
        assertEquals(before["activeComponents"].asLong() + 1, after["activeComponents"].asLong())
    }

    private companion object {
        val V1_V3 = Regex("^/?rest/api/[123]/")

        // Response types that are, or carry, v1-v3 Component DTOs.
        val COMPONENT_CARRIERS =
            listOf(Component::class.java, ComponentV3::class.java, ComponentsDTO::class.java, ArtifactComponentsDTO::class.java)
    }
}
