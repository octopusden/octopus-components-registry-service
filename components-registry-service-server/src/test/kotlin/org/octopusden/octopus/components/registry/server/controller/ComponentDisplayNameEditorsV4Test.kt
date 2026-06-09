package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Covers the component-form-improvements v4 surface:
 *  - displayName is required + unique (blank/absent → defaults to the component key;
 *    a duplicate display name is a 400 keyed `displayName`, distinct from the 409 for a
 *    duplicate component key);
 *  - /meta/java-versions + /meta/maven-versions return the configured option lists,
 *    numeric-sorted;
 *  - /{idOrName}/editors returns the component's owner + release managers + security
 *    champions (informational projection).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
@Tag("integration")
class ComponentDisplayNameEditorsV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentDisplayNameEditorsV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun unique(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createBody(
        name: String,
        displayName: String?,
        owner: String = "owner1",
        releaseManager: List<String> = emptyList(),
        securityChampion: List<String> = emptyList(),
    ): String {
        val displayNameJson = if (displayName == null) "" else """"displayName":"$displayName","""
        val rmJson = releaseManager.joinToString(",") { "\"$it\"" }
        val scJson = securityChampion.joinToString(",") { "\"$it\"" }
        return """{"name":"$name",$displayNameJson"componentOwner":"$owner",""" +
            """"releaseManager":[$rmJson],"securityChampion":[$scJson],""" +
            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
    }

    @Test
    @DisplayName("create with a blank/absent displayName defaults it to the component key")
    fun `create defaults displayName to component key when absent`() {
        val name = unique("disp_default")
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(name, displayName = null)),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.displayName").value(name))
    }

    @Test
    @DisplayName("create rejects a duplicate displayName with 400 keyed displayName")
    fun `create rejects duplicate displayName`() {
        val shared = unique("Shared Display")
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(unique("disp_one"), displayName = shared)),
            ).andExpect(status().isCreated)

        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(unique("disp_two"), displayName = shared)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("displayName")))
    }

    @Test
    @DisplayName("create rejects a duplicate component key with 400 keyed name")
    fun `create rejects duplicate component key`() {
        val name = unique("dup_key")
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(name, displayName = unique("Key One Display"))),
            ).andExpect(status().isCreated)

        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(name, displayName = unique("Key Two Display"))),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("name")))
    }

    @Test
    @DisplayName("update rejects changing displayName to one already used by another component")
    fun `update rejects duplicate displayName`() {
        val takenName = unique("Taken Display")
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(unique("disp_holder"), displayName = takenName)),
            ).andExpect(status().isCreated)

        val otherName = unique("disp_other")
        val otherBody =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(otherName, displayName = unique("Other Display"))),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val otherNode = objectMapper.readTree(otherBody)
        val otherId = otherNode["id"].asText()
        val version = otherNode["version"].asLong()

        mvc
            .perform(
                patch("/rest/api/4/components/$otherId")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"displayName":"$takenName"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("displayName")))
    }

    @Test
    @DisplayName("GET /meta/java-versions returns the configured Java versions, numeric-sorted")
    fun `meta java-versions returns configured list sorted`() {
        val result =
            mvc
                .perform(get("/rest/api/4/components/meta/java-versions").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andReturn().response.contentAsString
        val versions = objectMapper.readTree(result).map { it.asText() }
        assert(versions == listOf("1.8", "11", "17", "21", "25")) {
            "expected configured Java versions numeric-sorted; got $versions"
        }
    }

    @Test
    @DisplayName("GET /meta/maven-versions returns the configured Maven versions, numeric-sorted")
    fun `meta maven-versions returns configured list sorted`() {
        val result =
            mvc
                .perform(get("/rest/api/4/components/meta/maven-versions").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andReturn().response.contentAsString
        val versions = objectMapper.readTree(result).map { it.asText() }
        assert(versions == listOf("2.2.1", "3", "3.3.9", "3.6", "3.6.3", "3.8", "3.9")) {
            "expected configured Maven versions numeric-sorted; got $versions"
        }
    }

    @Test
    @DisplayName("GET /{id}/editors returns owner + ordered release managers + security champions")
    fun `editors returns owner rm sc`() {
        val name = unique("disp_editors")
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            createBody(
                                name,
                                displayName = unique("Editors Display"),
                                owner = "ownerA",
                                releaseManager = listOf("rm1", "rm2"),
                                securityChampion = listOf("sc1"),
                            ),
                        ),
                ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        val id = objectMapper.readTree(body)["id"].asText()

        mvc
            .perform(get("/rest/api/4/components/$id/editors").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.componentOwner").value("ownerA"))
            .andExpect(jsonPath("$.releaseManagers[0]").value("rm1"))
            .andExpect(jsonPath("$.releaseManagers[1]").value("rm2"))
            .andExpect(jsonPath("$.securityChampions[0]").value("sc1"))
    }
}
