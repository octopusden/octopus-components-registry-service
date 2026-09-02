package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeAll
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Pins the auth + id-or-name resolution contract of
 * `GET /rest/api/4/components/{id}/archive-readiness`. The endpoint is read-only pre-flight for
 * the archive/delete flow, so it requires the exact same authorization as `deleteComponent`
 * (`ACCESS_COMPONENTS` + `canDeleteComponent`, i.e. `DELETE_COMPONENTS`).
 *
 * `@ActiveProfiles("common", "ft-db")` is required, not incidental: a bare `@SpringBootTest`
 * with no active profile retries Spring Cloud Config's bootstrap against an unreachable server
 * for 12+ hours (confirmed via thread dump on this branch). `bootstrap-ft-db.yml` disables that
 * retry loop. Do not remove or weaken this annotation.
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
class ArchiveReadinessControllerTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun createSimpleComponent(name: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","displayName":"$name","componentOwner":"owner",
                            |"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}
                            """.trimMargin(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    @Test
    @DisplayName("endpoint returns 200 with ready and entries for a component with no external targets")
    fun endpointReturns200WithReadyAndEntriesForComponentWithNoExternalTargets() {
        val id = createSimpleComponent("arc-test-${System.nanoTime()}")
        mvc
            .perform(get("/rest/api/4/components/$id/archive-readiness").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.entries").isArray)
    }

    @Test
    @DisplayName("endpoint resolves by component name")
    fun endpointResolvesByComponentName() {
        val name = "arc-byname-${System.nanoTime()}"
        createSimpleComponent(name)
        mvc
            .perform(get("/rest/api/4/components/$name/archive-readiness").with(adminJwt()))
            .andExpect(status().isOk)
    }

    @Test
    @DisplayName("unknown component yields 404")
    fun unknownComponentYields404() {
        mvc
            .perform(get("/rest/api/4/components/does-not-exist-ever/archive-readiness").with(adminJwt()))
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("caller without DELETE_COMPONENTS is rejected")
    fun callerWithoutDeleteComponentsIsRejected() {
        val id = createSimpleComponent("arc-perm-${System.nanoTime()}")
        // viewerJwt has ACCESS_COMPONENTS but not DELETE_COMPONENTS
        mvc
            .perform(get("/rest/api/4/components/$id/archive-readiness").with(viewerJwt()))
            .andExpect(status().isForbidden)
    }

    // Resolving by a component NAME that happens to be a valid UUID string must still work: the
    // UUID branch of ArchiveReadinessService.resolveComponent uses findById(uuid).orElse(null),
    // NOT orElseThrow, specifically so a UUID that parses but does not match any real component
    // id falls through to the findByComponentKey lookup below it instead of throwing early.
    @Test
    @DisplayName("resolving by a component name that happens to be a valid UUID string still works")
    fun endpointResolvesByUuidShapedName() {
        val uuidShapedName = UUID.randomUUID().toString()
        createSimpleComponent(uuidShapedName)
        mvc
            .perform(get("/rest/api/4/components/$uuidShapedName/archive-readiness").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ready").value(true))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(ArchiveReadinessControllerTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
