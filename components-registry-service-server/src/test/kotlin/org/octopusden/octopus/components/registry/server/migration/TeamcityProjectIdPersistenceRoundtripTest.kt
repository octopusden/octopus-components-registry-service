package org.octopusden.octopus.components.registry.server.migration

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
 * Persistence round-trip for the v2 multi-row `component_teamcity_projects` table.
 *
 * Replaces the old [TeamcityProjectIdPersistenceRoundtripTest] that tested the
 * now-deleted scalar `teamcityProjectId`/`teamcityProjectUrl` columns on
 * `components`. The new test seeds its own component via the v4 CRUD API so it
 * does not depend on auto-migrate / the ImportServiceImpl DSL pipeline (phase 5
 * stub returns empty — MIG-039 will restore that path).
 *
 * Three scenarios:
 *  1. POST with `teamcityProjects` list → GET returns the persisted row.
 *  2. PATCH replaces the TC project list → old row gone, new row at sortOrder=0.
 *  3. PATCH with empty list clears all TC project rows.
 *
 * `teamcity.base-url` is set via `properties` so projectUrl is non-null and
 * verifiable without starting a real TC server.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["teamcity.base-url=https://tc.example.com"],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
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

    // -- Helpers --------------------------------------------------------------

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(
        name: String,
        tcProjectId: String? = null,
    ): JsonNode {
        val body = buildMap<String, Any?> {
            put("name", name)
            // Strict-contract minimum fields (UI-swift-sloth): every component
            // must have a group and a baseConfiguration.build.buildSystem.
            // componentOwner is a required (non-blank) v4 create field.
            put("componentOwner", "owner1")
            put("group", mapOf("groupKey" to "org.example.test", "isFake" to false))
            put("baseConfiguration", mapOf("build" to mapOf("buildSystem" to "MAVEN")))
            if (tcProjectId != null) {
                put("teamcityProjects", listOf(mapOf("projectId" to tcProjectId)))
            }
        }
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun getComponent(id: String): JsonNode {
        val response =
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(response)
    }

    // -- Tests ----------------------------------------------------------------

    @Test
    @DisplayName("v2: teamcityProject round-trips via POST + GET (projectId + projectUrl composed from base-url)")
    fun teamcityProject_createAndGet() {
        val created = createComponent(uniqueName("TC_RT"), tcProjectId = "TcProj_Alpha")
        val id = created["id"].asText()

        val detail = getComponent(id)
        val tcProjects = detail["teamcityProjects"]

        assertTrue(tcProjects.isArray && tcProjects.size() == 1, "expected exactly one TC project row")
        val row = tcProjects[0]
        assertEquals("TcProj_Alpha", row["projectId"].asText())
        // projectUrl is composed at read-time: base-url + "/project/" + projectId
        assertEquals("https://tc.example.com/project/TcProj_Alpha", row["projectUrl"].asText())
        assertEquals(0, row["sortOrder"].asInt())
    }

    @Test
    @DisplayName("v2: PATCH teamcityProjects replaces existing row — old row deleted, new row at sortOrder=0")
    fun teamcityProject_patchReplaces() {
        val created = createComponent(uniqueName("TC_PATCH"), tcProjectId = "TcProj_Initial")
        val id = created["id"].asText()
        val version = getComponent(id)["version"].asLong()

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf(
                                "version" to version,
                                "teamcityProjects" to listOf(mapOf("projectId" to "TcProj_Replaced")),
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)

        val updated = getComponent(id)
        val tcProjects = updated["teamcityProjects"]

        assertTrue(tcProjects.isArray && tcProjects.size() == 1, "expected exactly one row after PATCH replace")
        val row = tcProjects[0]
        assertEquals("TcProj_Replaced", row["projectId"].asText(), "old row deleted, new row present")
        assertEquals(0, row["sortOrder"].asInt(), "single row is at sortOrder=0")
        assertEquals("https://tc.example.com/project/TcProj_Replaced", row["projectUrl"].asText())
    }

    @Test
    @DisplayName("v2: PATCH teamcityProjects with empty list clears all TC project rows")
    fun teamcityProject_patchClearsAll() {
        val created = createComponent(uniqueName("TC_CLEAR"), tcProjectId = "TcProj_ToBeCleared")
        val id = created["id"].asText()
        val version = getComponent(id)["version"].asLong()

        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            mapOf("version" to version, "teamcityProjects" to emptyList<Any>()),
                        ),
                    ),
            ).andExpect(status().isOk)

        val updated = getComponent(id)
        val tcProjects = updated["teamcityProjects"]
        assertTrue(
            tcProjects == null || (tcProjects.isArray && tcProjects.size() == 0),
            "expected no TC project rows after PATCH with empty list",
        )
    }
}
