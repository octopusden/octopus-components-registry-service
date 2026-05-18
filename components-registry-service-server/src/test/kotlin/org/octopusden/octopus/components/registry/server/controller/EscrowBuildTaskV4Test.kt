package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-039 / RES-008 — `escrow.buildTask` must round-trip the V4 editor API.
 *
 * The Groovy DSL exposes `escrow { buildTask = "..." }`, the schema-v2
 * `escrow_build_task` column persists it, and `EntityMappers` applies it to
 * `EscrowModuleConfig.escrow.buildTask` on the resolver path. Pre-fix, the V4
 * surface dropped the value entirely: the editor `EscrowAspect{Request,Response}`
 * DTOs had no `buildTask` field, `V4Mappers.escrowAspectResponse()` ignored
 * the `escrowBuildTask` column, and `escrow.buildTask` was missing from
 * `SCALAR_ATTRIBUTE_PATHS` — so `GET /rest/api/4/components/{id}` returned no
 * buildTask, and `POST .../field-overrides { "overriddenAttribute":
 * "escrow.buildTask" }` returned 400 as an unknown attribute.
 *
 * TESTONE (`common/TestComponents.kts:8-19`) declares
 * `escrow { buildTask = "clean build -x test" }` at the top level. After
 * auto-migrate the DB-backed v4 detail endpoint must surface that exact value
 * on the BASE configuration row's `escrow.buildTask`, and a field-override
 * with `overriddenAttribute = "escrow.buildTask"` must persist successfully.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@Timeout(120)
class EscrowBuildTaskV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(EscrowBuildTaskV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("RES-008 GET /v4/components/{TESTONE}: escrow.buildTask survives DB round-trip")
    fun escrowBuildTask_baseConfiguration_exposedOnV4Detail() {
        val body =
            mvc
                .perform(get("/rest/api/4/components/TESTONE").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(body)
        val baseConfig =
            detail
                .path("configurations")
                .firstOrNull { it.path("rowType").asText() == "BASE" }
                ?: error("TESTONE detail must include a BASE configuration row; got: ${detail.path("configurations")}")
        val buildTask = baseConfig.path("escrow").path("buildTask")
        assertTrue(
            !buildTask.isMissingNode && !buildTask.isNull,
            "Expected configurations[BASE].escrow.buildTask to be populated; full response was $body",
        )
        assertEquals(
            "clean build -x test",
            buildTask.asText(),
            "configurations[BASE].escrow.buildTask must equal the DSL value",
        )
    }

    @Test
    @DisplayName(
        "RES-008 POST /v4/components/{TESTONE}/field-overrides with escrow.buildTask is accepted",
    )
    fun escrowBuildTask_fieldOverride_acceptedAsScalar() {
        // Resolve TESTONE id first — field-override endpoints take UUID, not name.
        val detail =
            objectMapper.readTree(
                mvc
                    .perform(get("/rest/api/4/components/TESTONE").with(editorJwt()))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        val id = detail["id"].asText()
        val payload =
            """
            {
              "overriddenAttribute": "escrow.buildTask",
              "versionRange": "[99.0.0,)",
              "value": "build -x test -PskipEscrow"
            }
            """.trimIndent()

        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().is2xxSuccessful)
    }
}
