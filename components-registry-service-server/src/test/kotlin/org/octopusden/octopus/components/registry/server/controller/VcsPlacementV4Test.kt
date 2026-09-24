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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * ONB-001 — VCS entry placement (`sourcePath` / `checkoutDirectory`) through the real v4 write
 * and read paths (ft-db = H2 + auto-migrate).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class VcsPlacementV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(VcsPlacementV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("ONB-001: v4 round-trip of sourcePath and checkoutDirectory")
    fun `v4 round-trip`() {
        val id = newComponent()
        patchComponent(
            id,
            """"baseConfiguration":{"vcsEntries":[""" +
                """{"vcsPath":"$REPO_A","repositoryType":"GIT","sourcePath":"mapper"},""" +
                """{"vcsPath":"$REPO_B","repositoryType":"GIT","sourcePath":"data","checkoutDirectory":"feature"}]}""",
        ).andExpect(status().isOk)

        val entries = baseVcsEntries(getComponent(id))
        assertEquals(listOf("mapper", "data"), entries.map { it["sourcePath"]?.asText() })
        assertEquals(listOf(null, "feature"), entries.map { it["checkoutDirectory"]?.takeUnless(JsonNode::isNull)?.asText() })
    }

    @Test
    @DisplayName("ONB-001: blank sourcePath / checkoutDirectory are stored and returned as absent")
    fun `blank values are absent`() {
        val id = newComponent()
        patchComponent(
            id,
            """"baseConfiguration":{"vcsEntries":[{"vcsPath":"$REPO_A","repositoryType":"GIT","checkoutDirectory":"","sourcePath":" "}]}""",
        ).andExpect(status().isOk)

        val entry = baseVcsEntries(getComponent(id)).single()
        assertTrue(entry["sourcePath"] == null || entry["sourcePath"].isNull, "sourcePath must be absent: $entry")
        assertTrue(entry["checkoutDirectory"] == null || entry["checkoutDirectory"].isNull, "checkoutDirectory must be absent: $entry")
    }

    @Test
    @DisplayName("ONB-001: a vcs.settings marker row carries the placement fields")
    fun `marker row round-trip`() {
        val id = newComponent()
        val body =
            createVcsMarker(
                id,
                """{"vcsPath":"$REPO_A","repositoryType":"GIT"},""" +
                    """{"vcsPath":"$REPO_B","repositoryType":"GIT","sourcePath":"data","checkoutDirectory":"feature"}""",
            ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString

        val second = objectMapper.readTree(body)["markerChildren"]["vcsEntries"][1]
        assertEquals("data", second["sourcePath"].asText())
        assertEquals("feature", second["checkoutDirectory"].asText())
    }

    @Test
    @DisplayName("ONB-001: an invalid base VCS write is a 400 whose errorMessage names the entry field")
    fun `base write error shape`() {
        val id = newComponent()
        val error =
            patchComponent(
                id,
                """"baseConfiguration":{"vcsEntries":[{"vcsPath":"$REPO_A"},{"vcsPath":"$REPO_B"}]}""",
            ).andExpect(status().isBadRequest)
                .errorMessage()
        assertTrue(error.startsWith("vcsEntries[1].checkoutDirectory: "), error)
    }

    @Test
    @DisplayName("ONB-001: a field-override write validates the vcs.settings marker row, unprefixed")
    fun `marker row validated`() {
        val id = newComponent()
        val error =
            createVcsMarker(id, """{"vcsPath":"$REPO_A"},{"vcsPath":"$REPO_B"}""")
                .andExpect(status().isBadRequest)
                .errorMessage()
        assertTrue(error.startsWith("vcsEntries[1].checkoutDirectory: "), error)
    }

    @Test
    @DisplayName("ONB-001: a marker-row error in a combined PATCH is prefixed with its fieldOverrides index (create path)")
    fun `combined patch prefixes marker error on create`() {
        val id = newComponent()
        val error =
            patchComponent(
                id,
                """"fieldOverrides":[""" +
                    """{"overriddenAttribute":"build.buildFilePath","versionRange":"[5.0,6.0)","value":"FileA"},""" +
                    """{"overriddenAttribute":"vcs.settings","versionRange":"[1.0,2.0)",""" +
                    """"markerChildren":{"vcsEntries":[{"vcsPath":"$REPO_A"},{"vcsPath":"$REPO_B"}]}}]""",
            ).andExpect(status().isBadRequest)
                .errorMessage()
        assertTrue(error.startsWith("fieldOverrides[1].vcsEntries[1].checkoutDirectory: "), error)
    }

    @Test
    @DisplayName("ONB-001: a marker-row error in a combined PATCH is prefixed with its fieldOverrides index (update path)")
    fun `combined patch prefixes marker error on update`() {
        val id = newComponent()
        val markerId = objectMapper.readTree(createVcsMarker(id, """{"vcsPath":"$REPO_A"}""").andReturn().response.contentAsString)["id"].asText()
        val error =
            patchComponent(
                id,
                """"fieldOverrides":[""" +
                    """{"overriddenAttribute":"build.buildFilePath","versionRange":"[5.0,6.0)","value":"FileA"},""" +
                    """{"id":"$markerId","overriddenAttribute":"vcs.settings","versionRange":"[1.0,2.0)",""" +
                    """"markerChildren":{"vcsEntries":[{"vcsPath":"$REPO_A","checkoutDirectory":"core"}]}}]""",
            ).andExpect(status().isBadRequest)
                .errorMessage()
        assertTrue(error.startsWith("fieldOverrides[1].vcsEntries[0].checkoutDirectory: "), error)
    }

    @Test
    @DisplayName("ONB-001: a base VCS write on a component with a linked TeamCity project warns that the chain must be recreated")
    fun `chain mismatch warning`() {
        val id = linkedComponent()
        val detail =
            patchComponent(id, """"baseConfiguration":{"vcsEntries":[{"vcsPath":"$REPO_A"},{"vcsPath":"$REPO_B","checkoutDirectory":"feature"}]}""")
                .andExpect(status().isOk)
                .json()
        assertEquals(listOf(CHAIN_WARNING), detail["warnings"].map { it.asText() })
        assertEquals(0, getComponent(id)["warnings"].size(), "GET carries no warnings")
    }

    @Test
    @DisplayName("ONB-001: no chain warning without a linked project, on an unrelated edit, or on marker-row writes")
    fun `no chain mismatch warning`() {
        val unlinked = newComponent()
        val vcs = """"baseConfiguration":{"vcsEntries":[{"vcsPath":"$REPO_A"}]}"""
        assertEquals(0, patchComponent(unlinked, vcs).andExpect(status().isOk).json()["warnings"].size())

        val linked = linkedComponent()
        assertEquals(0, patchComponent(linked, """"displayName":"Renamed $linked"""").andExpect(status().isOk).json()["warnings"].size())

        val marker = createVcsMarker(linked, """{"vcsPath":"$REPO_A"}""").andExpect(status().is2xxSuccessful).json()
        assertTrue(marker["warnings"] == null, "field-override responses carry no warnings")
        val patched =
            patchComponent(
                linked,
                """"fieldOverrides":[{"id":"${marker["id"].asText()}","overriddenAttribute":"vcs.settings","versionRange":"[1.0,2.0)",""" +
                    """"markerChildren":{"vcsEntries":[{"vcsPath":"$REPO_B"}]}}]""",
            ).andExpect(status().isOk)
                .json()
        assertEquals(0, patched["warnings"].size())
    }

    private fun linkedComponent(): String =
        newComponent().also { id ->
            patchComponent(id, """"teamcityProjects":[{"projectId":"TestProject_${id.take(8)}"}]""").andExpect(status().isOk)
        }

    private fun ResultActions.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

    private fun ResultActions.errorMessage(): String = objectMapper.readTree(andReturn().response.contentAsString)["errorMessage"].asText()

    private fun baseVcsEntries(detail: JsonNode): List<JsonNode> = detail["configurations"].first { it["rowType"].asText() == "BASE" }["vcsEntries"].toList()

    private fun newComponent(): String {
        val name = "vcs-placement-${UUID.randomUUID().toString().take(8)}"
        val body =
            mvc
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

    private fun createVcsMarker(
        componentId: String,
        entries: String,
        range: String = "[1.0,2.0)",
    ): ResultActions =
        mvc.perform(
            post("/rest/api/4/components/$componentId/field-overrides")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"overriddenAttribute":"vcs.settings","versionRange":"$range","markerChildren":{"vcsEntries":[$entries]}}""",
                ),
        )

    private fun getComponent(componentId: String): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$componentId").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun patchComponent(
        componentId: String,
        fieldsWithoutVersion: String,
    ): ResultActions =
        mvc.perform(
            patch("/rest/api/4/components/$componentId")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"version":${getComponent(componentId)["version"].asLong()},$fieldsWithoutVersion}"""),
        )

    companion object {
        private const val REPO_A = "ssh://git@example.test/proj/repo-a.git"
        private const val REPO_B = "ssh://git@example.test/proj/repo-b.git"
        private const val CHAIN_WARNING = "VCS entries changed; the TeamCity build chain no longer matches and must be recreated."
    }
}
