package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Paths
import java.util.UUID

/**
 * Error-handler hardening: clients hitting the V4 API with malformed input must get
 * a `4xx` with a consistent `ErrorResponse { errorMessage }` body, not a `500` and
 * not Boot's default `/error` envelope (which has a different shape).
 *
 * Covers the gaps identified after the `sort=name,asc → 500` report:
 *   - `PropertyReferenceException` from Spring Data (unknown sort field) → 400
 *   - lenient API↔entity sort translation so `sort=name` works (mapped to `componentKey`)
 *   - malformed JSON request body (`HttpMessageNotReadableException`) → 400
 *   - bad path-variable type (`MethodArgumentTypeMismatchException`) → 400
 *   - all of the above return `ErrorResponse` shape, not Boot's `DefaultErrorAttributes`
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Import(ErrorHandlingHardeningTest.TestThrowingController::class)
@Tag("integration")
class ErrorHandlingHardeningTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ErrorHandlingHardeningTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("sort=name,asc is translated to componentKey and returns 200")
    fun listComponents_sortByApiFieldName_translatesToComponentKey() {
        val a = "errhard_a_${UUID.randomUUID().toString().take(8)}"
        val b = "errhard_b_${UUID.randomUUID().toString().take(8)}"
        createComponent(b)
        createComponent(a)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("size", "200")
                        .param("sort", "name,asc"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val names =
            objectMapper.readTree(body)["content"]
                .map { it["name"].asText() }
                .filter { it.startsWith("errhard_") }
        assert(names.indexOf(a) < names.indexOf(b)) {
            "expected '$a' to come before '$b' under sort=name,asc, got $names"
        }
    }

    @Test
    @DisplayName("sort=<unknown> returns 400 ErrorResponse, not 500")
    fun listComponents_sortByUnknownField_returns400ErrorResponse() {
        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("sort", "doesNotExist,asc"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").exists())
            .andExpect(jsonPath("$.errorMessage", containsSubstring("doesNotExist")))
    }

    @Test
    @DisplayName("malformed JSON body on POST returns 400 ErrorResponse, not Boot's /error envelope")
    fun createComponent_malformedJson_returns400ErrorResponse() {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name": "broken", """),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").exists())
    }

    @Test
    @DisplayName("ResponseStatusException(CONFLICT) is preserved as 409, not collapsed to 500 by Throwable catch-all")
    fun responseStatusException_preservesOriginalStatusCode() {
        val body =
            mvc
            .perform(
                get("/test-error-helpers/response-status-conflict")
                    .with(viewerJwt()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorMessage").exists())
            .andExpect(jsonPath("$.errorMessage", containsSubstring("synthetic conflict")))
            .andReturn()
            .response.contentAsString

        assertFalse(
            objectMapper.readTree(body).has("errorCode"),
            "plain error responses must not serialize errorCode:null; legacy compat expects the field to be absent",
        )
    }

    @Test
    @DisplayName("non-UUID path variable on PATCH returns 400 ErrorResponse")
    fun patchComponent_nonUuidPath_returns400ErrorResponse() {
        mvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/rest/api/4/components/not-a-uuid")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"displayName":"x"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").exists())
    }

    private fun createComponent(name: String) {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"$name",""" +
                            """"componentOwner":"owner1",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
            ).andExpect(status().isCreated)
    }

    private fun containsSubstring(needle: String) =
        org.hamcrest.Matchers.containsString(needle)

    /**
     * Test-only controller used to drive a `ResponseStatusException` through the
     * full DispatcherServlet → `@ControllerAdvice` chain. Service-layer code
     * (e.g. `GitHistoryImportServiceImpl.preflight`) throws `ResponseStatusException`
     * with a non-500 status; the broad `@ExceptionHandler(Throwable::class)`
     * catch-all must NOT swallow it back into a 500. Registered via `@Import`
     * on the test class — a plain `@RestController` bean, not a `@TestConfiguration`,
     * to avoid CGLIB proxying of an MVC controller.
     */
    @RestController
    @RequestMapping("/test-error-helpers")
    class TestThrowingController {
        @GetMapping("/response-status-conflict")
        fun conflict(): Nothing =
            throw ResponseStatusException(HttpStatus.CONFLICT, "synthetic conflict for handler test")
    }
}
