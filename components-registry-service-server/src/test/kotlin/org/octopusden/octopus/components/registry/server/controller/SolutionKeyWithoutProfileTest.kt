package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
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
 * Keys that break the profile key rules stay accepted when no profile is involved: a create
 * without `profile`, a rename and a solution-flag change are checked exactly as before profiles
 * existed. Profile field rules apply only to a create that names a profile.
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
class SolutionKeyWithoutProfileTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(SolutionKeyWithoutProfileTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun suffix() = UUID.randomUUID().toString().take(8)

    private fun create(
        name: String,
        solution: Boolean,
    ): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","componentOwner":"owner1","solution":$solution,""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun detail(id: String) =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun patchComponent(
        id: String,
        fields: String,
    ) {
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${detail(id)["version"].asLong()},$fields}"""),
            ).andExpect(status().isOk)
    }

    @Test
    @DisplayName("a solution whose key has no solution word is created when no profile is named")
    fun solutionWithoutMarker_created() {
        val id = create("payments-${suffix()}", solution = true)

        assert(detail(id)["solution"].asBoolean()) { "expected solution=true" }
    }

    @Test
    @DisplayName("a regular component whose key contains 'solution' is created when no profile is named")
    fun regularWithMarker_created() {
        val name = "resolution-service-${suffix()}"
        val id = create(name, solution = false)

        assert(detail(id)["name"].asText() == name)
    }

    @Test
    @DisplayName("a regular component is renamed to a key containing 'solution' without a profile check")
    fun renameToMarker_accepted() {
        val id = create("payments-${suffix()}", solution = false)
        val newName = "payments-solution-${suffix()}"

        patchComponent(id, """"name":"$newName"""")

        assert(detail(id)["name"].asText() == newName)
    }

    @Test
    @DisplayName("a solution keyed with 'solution' is unflagged without a profile check")
    fun unflagSolution_accepted() {
        val id = create("payments-solution-${suffix()}", solution = true)

        patchComponent(id, """"solution":false""")

        assert(!detail(id)["solution"].asBoolean()) { "expected solution=false" }
    }
}
