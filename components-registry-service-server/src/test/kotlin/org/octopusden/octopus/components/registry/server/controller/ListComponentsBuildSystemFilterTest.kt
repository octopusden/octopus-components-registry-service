package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
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

@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsBuildSystemFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsBuildSystemFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponentWithBuildSystem(
        name: String,
        buildSystem: String,
    ) {
        val id = createComponent(name)
        val version = getComponentVersion(id)
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":$version,"buildConfiguration":{"buildSystem":"$buildSystem"}}""",
                    ),
            ).andExpect(status().isOk)
    }

    private fun createComponentWithoutBuildSystem(name: String) {
        createComponent(name)
    }

    private fun createComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","displayName":"$name"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun getComponentVersion(id: String): Long {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["version"].asLong()
    }

    @Test
    @DisplayName("listComponents with ?buildSystem=GRADLE returns only components with that buildSystem")
    fun listComponents_byBuildSystem_returnsMatching() {
        val gradleComp = uniqueName("bs_gradle")
        val mavenComp = uniqueName("bs_maven")
        val noBuildComp = uniqueName("bs_none")

        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")
        createComponentWithoutBuildSystem(noBuildComp)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("buildSystem", "GRADLE")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val names = json["content"].map { it["name"].asText() }.toSet()
        assert(names.contains(gradleComp)) { "expected $gradleComp in $names" }
        assert(!names.contains(mavenComp)) { "did not expect $mavenComp in $names" }
        assert(!names.contains(noBuildComp)) { "did not expect $noBuildComp in $names (no build config)" }
    }

    @Test
    @DisplayName("component without buildConfigurations is excluded when buildSystem filter is set")
    fun listComponents_noBuildConfig_excludedWhenFilterSet() {
        val noBuildComp = uniqueName("bs_edge_none")
        createComponentWithoutBuildSystem(noBuildComp)

        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("buildSystem", "GRADLE"),
            ).andExpect(status().isOk)
            .andExpect(
                jsonPath("$.content[?(@.name == '$noBuildComp')]").doesNotExist(),
            )
    }

    @Test
    @DisplayName("listComponents without buildSystem filter returns 200 (filter is optional)")
    fun listComponents_withoutBuildSystem_ok() {
        mvc.perform(get("/rest/api/4/components").with(viewerJwt())).andExpect(status().isOk)
    }

    @Test
    @DisplayName("listComponents with ?buildSystem=<unknown> returns empty page")
    fun listComponents_byUnknownBuildSystem_returnsEmpty() {
        val unknownBs = uniqueName("UNKNOWN_BS")

        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("buildSystem", unknownBs),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }
}
