package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-094-EXPR: expression support on the v4 generic-artifact write path.
 *
 * Verifies that:
 *  - a templated path `releases/foo/${'$'}{version}/foo.tar.gz` is accepted and stored verbatim.
 *  - an invalid SpEL expression is rejected with 400.
 *  - a plain literal path is still accepted (regression guard).
 *  - a comma in a single path item is rejected with 400.
 *  - a dot-only segment is rejected with 400.
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
class GenericArtifactExpressionV4Test {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(GenericArtifactExpressionV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    // -------------------------------------------------------------------------
    // SYS-094-EXPR-001: ${version} template accepted, stored verbatim
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-EXPR-001: POST component with genericArtifact path containing \${version} " +
            "→ 2xx; template preserved verbatim on GET",
    )
    fun `SYS-094-EXPR-001 version template accepted and stored verbatim`() {
        val templatePath = "releases/expr-test/\${version}/expr-test.tar.gz"
        val createBody = componentBody("ga-expr-001", templatePath)

        val createResponse =
            mvc.perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody),
            ).andExpect(status().is2xxSuccessful)
                .andReturn().response.contentAsString

        val id = objectMapper.readTree(createResponse)["id"].asText()

        val getBody =
            mvc.perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString

        val baseConfig =
            objectMapper.readTree(getBody)
                .path("configurations")
                .firstOrNull { it.path("rowType").asText() == "BASE" }
                ?: error("Component must have a BASE row; response=$getBody")

        val artifacts = baseConfig.path("genericArtifacts")
        assertEquals(1, artifacts.size(), "Expected 1 genericArtifact; got: $artifacts")
        assertEquals(templatePath, artifacts[0].path("path").asText(), "Template must be stored verbatim")
    }

    // -------------------------------------------------------------------------
    // SYS-094-EXPR-002: invalid SpEL expression → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-EXPR-002: POST component with genericArtifact path containing invalid SpEL " +
            "\${unknownProp} → 400",
    )
    fun `SYS-094-EXPR-002 invalid SpEL expression rejected with 400`() {
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentBody("ga-expr-002", "releases/bad/\${unknownProp}/file")),
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // SYS-094-EXPR-003: plain literal path still accepted (regression)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-EXPR-003: POST component with plain literal genericArtifact path → 2xx",
    )
    fun `SYS-094-EXPR-003 plain literal path accepted`() {
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentBody("ga-expr-003", "releases/demo-tool/1.0.0/demo-tool.tar.gz")),
        ).andExpect(status().is2xxSuccessful)
    }

    // -------------------------------------------------------------------------
    // SYS-094-EXPR-004: comma in a single path item → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-EXPR-004: POST component with comma in a single genericArtifact path → 400",
    )
    fun `SYS-094-EXPR-004 comma in single path rejected with 400`() {
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentBody("ga-expr-004", "releases/1.0/a.tar.gz,releases/1.0/b.tar.gz")),
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // SYS-094-EXPR-005: dot-only segment → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "SYS-094-EXPR-005: POST component with dot-only segment in genericArtifact path → 400",
    )
    fun `SYS-094-EXPR-005 dot-only segment rejected with 400`() {
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(componentBody("ga-expr-005", "releases/../../../etc/passwd")),
        ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------

    private fun componentBody(nameSuffix: String, genericPath: String) =
        """
        {
          "name": "generic-expr-$nameSuffix",
          "componentOwner": "owner1",
          "group": {"groupKey": "org.example.test", "isFake": false},
          "baseConfiguration": {
            "genericArtifacts": [{"path": "$genericPath"}]
          }
        }
        """.trimIndent()
}
