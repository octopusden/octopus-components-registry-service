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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * V4-BASE-001..V4-BASE-004: V4 API tests for `buildToolBeans` on the BASE row.
 *
 *  - V4-BASE-001: GET `TEST_COMPONENT_BUILD_TOOLS` after auto-migrate surfaces 2 beans
 *    in the BASE configuration row.
 *  - V4-BASE-002: POST create component with `buildToolBeans` in `baseConfiguration`
 *    → 2xx and beans returned on the subsequent GET.
 *  - V4-BASE-003: PATCH base config replaces `buildToolBeans` when present in the
 *    request body; the new list is returned on GET.
 *  - V4-BASE-004: PATCH base config with `buildToolBeans` absent preserves existing
 *    beans (null = "don't touch" semantics).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class BuildToolBeansBaseV4Test {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(BuildToolBeansBaseV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    // -------------------------------------------------------------------------
    // V4-BASE-001: GET imported component → 2 buildToolBeans on BASE row
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-BASE-001: GET TEST_COMPONENT_BUILD_TOOLS after auto-migrate → 2 buildToolBeans " +
            "(oracleDatabase + kProduct) on BASE configuration row",
    )
    fun v4base001_importedComponent_hasBuildToolBeansOnBase() {
        val body =
            mvc
                .perform(get("/rest/api/4/components/TEST_COMPONENT_BUILD_TOOLS").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(body)
        val baseConfig =
            detail
                .path("configurations")
                .firstOrNull { it.path("rowType").asText() == "BASE" }
                ?: error("TEST_COMPONENT_BUILD_TOOLS must include a BASE configuration row; response=$body")
        val beans = baseConfig.path("buildToolBeans")
        assertTrue(beans.isArray, "buildToolBeans must be an array; response=$body")
        assertEquals(2, beans.size(), "BASE row must have 2 buildToolBeans; got: $beans")
        val beanTypes = beans.map { it.path("beanType").asText() }.toSet()
        assertTrue(
            "oracleDatabase" in beanTypes,
            "Expected oracleDatabase bean; got beanTypes=$beanTypes",
        )
        assertTrue(
            "kProduct" in beanTypes,
            "Expected kProduct bean; got beanTypes=$beanTypes",
        )
    }

    // -------------------------------------------------------------------------
    // V4-BASE-002: POST create component with buildToolBeans → beans persisted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-BASE-002: POST /v4/components with buildToolBeans in baseConfiguration " +
            "→ 2xx and beans returned on GET",
    )
    fun v4base002_createComponentWithBuildToolBeans_beansPersistedAndReturned() {
        val createBody =
            """
            {
              "name": "btb-base-v4-test-002",
              "baseConfiguration": {
                "buildToolBeans": [
                  {
                    "beanType": "oracleDatabase",
                    "toolType": "ORA",
                    "settingsProperty": "oracle.prop",
                    "versionPattern": "[11,)",
                    "edition": null
                  },
                  {
                    "beanType": "odbc",
                    "versionPattern": "[1,)"
                  }
                ]
              }
            }
            """.trimIndent()
        val createResponse =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody),
                ).andExpect(status().is2xxSuccessful)
                .andExpect(jsonPath("$.configurations[?(@.rowType=='BASE')].buildToolBeans").isArray)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(createResponse)["id"].asText()

        val getBody =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(getBody)
        val baseConfig =
            detail
                .path("configurations")
                .firstOrNull { it.path("rowType").asText() == "BASE" }
                ?: error("Created component must have a BASE row; response=$getBody")
        val beans = baseConfig.path("buildToolBeans")
        assertEquals(2, beans.size(), "POST create must persist 2 buildToolBeans; got: $beans")
        val beanTypes = beans.map { it.path("beanType").asText() }.toSet()
        assertTrue("oracleDatabase" in beanTypes, "Expected oracleDatabase; beanTypes=$beanTypes")
        assertTrue("odbc" in beanTypes, "Expected odbc; beanTypes=$beanTypes")
    }

    // -------------------------------------------------------------------------
    // V4-BASE-003: PATCH replaces buildToolBeans when present
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-BASE-003: PATCH /v4/components/{id} with buildToolBeans in baseConfiguration " +
            "→ list is replaced; GET returns new beans",
    )
    fun v4base003_patchReplacesBuildToolBeans() {
        // Create with one oracle bean
        val createBody =
            """
            {
              "name": "btb-base-v4-test-003",
              "baseConfiguration": {
                "buildToolBeans": [
                  {"beanType": "oracleDatabase", "versionPattern": "[11,)"}
                ]
              }
            }
            """.trimIndent()
        val createResponse =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(createResponse)["id"].asText()
        val version = objectMapper.readTree(createResponse)["version"].asText()

        // PATCH: replace with a single kProduct bean
        val patchBody =
            """
            {
              "version": $version,
              "baseConfiguration": {
                "buildToolBeans": [
                  {"beanType": "kProduct", "versionPattern": "[03,)"}
                ]
              }
            }
            """.trimIndent()
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().is2xxSuccessful)

        // GET and verify replacement
        val getBody =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(getBody)
        val baseConfig =
            detail
                .path("configurations")
                .firstOrNull { it.path("rowType").asText() == "BASE" }
                ?: error("Patched component must have a BASE row; response=$getBody")
        val beans = baseConfig.path("buildToolBeans")
        assertEquals(1, beans.size(), "PATCH must replace to 1 bean; got: $beans")
        assertEquals("kProduct", beans[0].path("beanType").asText(), "Remaining bean must be kProduct")
    }

    // -------------------------------------------------------------------------
    // V4-BASE-004: PATCH without buildToolBeans → existing beans preserved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-BASE-004: PATCH /v4/components/{id} with baseConfiguration that omits buildToolBeans " +
            "→ existing beans unchanged (null = don't touch)",
    )
    fun v4base004_patchWithoutBuildToolBeans_preservesExistingBeans() {
        // Create with one bean
        val createBody =
            """
            {
              "name": "btb-base-v4-test-004",
              "baseConfiguration": {
                "buildToolBeans": [
                  {"beanType": "dProduct", "versionPattern": "[2,)"}
                ]
              }
            }
            """.trimIndent()
        val createResponse =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(createResponse)["id"].asText()
        val version = objectMapper.readTree(createResponse)["version"].asText()

        // PATCH: touch only displayName, omit buildToolBeans
        val patchBody =
            """
            {
              "version": $version,
              "displayName": "patched display name"
            }
            """.trimIndent()
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().is2xxSuccessful)

        // GET and verify beans are still there
        val getBody =
            mvc
                .perform(get("/rest/api/4/components/$id").with(editorJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(getBody)
        val baseConfig =
            detail
                .path("configurations")
                .firstOrNull { it.path("rowType").asText() == "BASE" }
                ?: error("Patched component must have a BASE row; response=$getBody")
        val beans = baseConfig.path("buildToolBeans")
        assertEquals(1, beans.size(), "PATCH without buildToolBeans must preserve existing beans; got: $beans")
        assertEquals("dProduct", beans[0].path("beanType").asText(), "Existing dProduct bean must remain")
    }
}
