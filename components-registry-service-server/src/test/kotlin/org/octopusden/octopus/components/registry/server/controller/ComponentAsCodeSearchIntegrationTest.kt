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
 * SYS-098: `GET /rest/api/4/components/as-code/search` end-to-end against the H2 `ft-db`
 * profile — the real bulk render, the real change stamp, and the controller contract.
 * Matching / shaping details are unit-tested in `ComponentCodeSearchServiceTest`.
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
class ComponentAsCodeSearchIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentAsCodeSearchIntegrationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-098: a value from the as-code view is found, with the line number of the as-code view")
    fun `SYS-098 a value from the as-code view is found at its as-code line`() {
        val owner = "srchowner${suffix()}"
        val name = newComponent(owner = owner)

        val hit = search(owner).single { it["componentKey"].asText() == name }

        val match = hit["matches"].single()
        assertEquals("componentOwner = \"$owner\"", match["text"].asText())
        val asCodeLines = getAsCode(name).lines()
        assertEquals(asCodeLines[match["line"].asInt() - 1].trim(), match["text"].asText())
        // The component block header as rendered (a key with '-' is double-quoted by the renderer).
        assertEquals(listOf(asCodeLines.first().removeSuffix(" {")), match["path"].map { it.asText() })
    }

    @Test
    @DisplayName("SYS-098: an edit is visible to the next search (index invalidated by the change stamp)")
    fun `SYS-098 an edit is visible to the next search`() {
        val before = "srchbefore${suffix()}"
        val after = "srchafter${suffix()}"
        val name = newComponent(owner = before)
        assertTrue(search(before).any { it["componentKey"].asText() == name })

        val detail = getDetail(name)
        mvc
            .perform(
                patch("/rest/api/4/components/${detail["id"].asText()}")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${detail["version"].asLong()},"componentOwner":"$after"}"""),
            ).andExpect(status().isOk)

        assertTrue(search(before).none { it["componentKey"].asText() == name })
        assertTrue(search(after).any { it["componentKey"].asText() == name })
    }

    @Test
    @DisplayName("SYS-098: too-short query and invalid regex are 400")
    fun `SYS-098 too-short query and invalid regex are 400`() {
        mvc.perform(get("/rest/api/4/components/as-code/search?q=a").with(adminJwt())).andExpect(status().isBadRequest)
        mvc
            .perform(get("/rest/api/4/components/as-code/search").param("q", "([a-z").param("regex", "true").with(adminJwt()))
            .andExpect(status().isBadRequest)
    }

    private fun suffix() = UUID.randomUUID().toString().take(8)

    private fun search(q: String): List<JsonNode> {
        val body =
            mvc
                .perform(get("/rest/api/4/components/as-code/search").param("q", q).with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["results"].toList()
    }

    private fun newComponent(owner: String): String {
        val name = "srch-${suffix()}"
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"$name","componentOwner":"$owner",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
            ).andExpect(status().is2xxSuccessful)
        return name
    }

    private fun getDetail(name: String): JsonNode =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/4/components/$name").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun getAsCode(name: String): String =
        mvc
            .perform(get("/rest/api/4/components/$name/as-code").with(adminJwt()))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
}
