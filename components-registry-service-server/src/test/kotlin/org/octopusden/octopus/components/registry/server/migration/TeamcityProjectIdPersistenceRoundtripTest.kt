package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.editorJwt
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class TeamcityProjectIdPersistenceRoundtripTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths
                .get(TeamcityProjectIdPersistenceRoundtripTest::class.java.getResource("/expected-data")!!.toURI())
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
    @DisplayName("teamcityProjectId round-trips via PATCH + GET")
    fun teamcityProjectId_roundtrip() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val version = getComponent(id)["version"].asLong()

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "teamcityProjectId" to "ProjectAlpha"),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)

        assertEquals("ProjectAlpha", getComponent(id)["teamcityProjectId"].asText())
    }

    @Test
    @DisplayName("teamcityProjectUrl round-trips via PATCH + GET")
    fun teamcityProjectUrl_roundtrip() {
        val summary = firstComponent()
        val id = summary["id"].asText()
        val version = getComponent(id)["version"].asLong()
        val url = "https://teamcity.example.com/project/ProjectAlpha"

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "teamcityProjectUrl" to url),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)

        assertEquals(url, getComponent(id)["teamcityProjectUrl"].asText())
    }

    @Test
    @DisplayName("teamcityProjectId: null in PATCH body does not clear an existing value (absent-vs-null limitation)")
    fun teamcityProjectId_nullDoesNotClear() {
        // The PATCH handler uses `?.let { }` semantics: a JSON null in the
        // request body is treated the same as the field being absent — the
        // existing persisted value is left unchanged. This is a documented
        // limitation noted in PR #168; explicit-null-as-clear is out of scope
        // for this PR. The test pins the current behaviour so any future
        // change to the semantics is visible as a test failure.
        val summary = firstComponent()
        val id = summary["id"].asText()
        var detail = getComponent(id)
        var version = detail["version"].asLong()

        // Establish a known value.
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "teamcityProjectId" to "ProjectBeta"),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)
        detail = getComponent(id)
        assertEquals("ProjectBeta", detail["teamcityProjectId"].asText(), "baseline value established")
        version = detail["version"].asLong()

        // PATCH with explicit null — must leave the stored value intact.
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "teamcityProjectId" to null),
                        ),
                    ),
            ).andExpect(status().is2xxSuccessful)

        assertEquals("ProjectBeta", getComponent(id)["teamcityProjectId"].asText(), "null patch must not clear")
    }
}
