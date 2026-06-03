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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * V4-MKR-001..V4-MKR-003: V4 API tests for the `build.buildTools` MARKER.
 *
 *  - V4-MKR-001: valid POST with `buildToolBeans` → 2xx + row persisted
 *  - V4-MKR-002: POST with both `buildToolBeans` and `vcsEntries` non-null → 400
 *    (MarkerChildrenPayload exactly-one-non-null contract)
 *  - V4-MKR-003: POST with `beanType="cProduct"` and `edition` non-null → 400
 *    (Oracle-only edition constraint)
 *
 * Tests are intentionally RED until Commit 8 adds:
 *  - `BuildToolBeanRequest` DTO + `MarkerChildrenPayload.buildToolBeans` field
 *  - V4Mappers `toMarkerChildrenPayload()` BUILD_TOOLS branch
 *  - `ComponentManagementServiceImpl` applyMarkerChildren / rejectExtraneousMarkerFields
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
class BuildToolBeansMarkerV4Test {

    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(BuildToolBeansMarkerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun createComponent(name: String): String {
        val body =
            """{"name": "$name",""" +
                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                """"baseConfiguration": {"build": {"buildSystem": "MAVEN"}}}"""
        val response =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(response)["id"].asText()
    }

    // -------------------------------------------------------------------------
    // V4-MKR-001: Valid POST build.buildTools marker → 2xx + buildToolBeans returned
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-MKR-001: POST field-override build.buildTools with valid oracleDatabase → 2xx " +
            "and buildToolBeans populated in response",
    )
    fun v4mkr001_validBuildToolsMarker_returns2xx() {
        val id = createComponent("btb-marker-v4-test-001")
        val payload =
            """
            {
              "overriddenAttribute": "build.buildTools",
              "versionRange": "[12,)",
              "markerChildren": {
                "buildToolBeans": [
                  {
                    "beanType": "oracleDatabase",
                    "toolType": "ORACLE",
                    "settingsProperty": "db",
                    "versionPattern": "[12,)",
                    "edition": null
                  }
                ]
              }
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().is2xxSuccessful)
            .andExpect(jsonPath("$.markerChildren.buildToolBeans").isArray)
            .andExpect(jsonPath("$.markerChildren.buildToolBeans[0].beanType").value("oracleDatabase"))
            .andExpect(jsonPath("$.markerChildren.buildToolBeans[0].versionPattern").value("[12,)"))
    }

    // -------------------------------------------------------------------------
    // V4-MKR-002: POST with both buildToolBeans AND vcsEntries non-null → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-MKR-002: POST field-override build.buildTools with both buildToolBeans and vcsEntries → 400 " +
            "(exactly-one-non-null MarkerChildrenPayload contract)",
    )
    fun v4mkr002_conflictingMarkerFields_returns400() {
        val id = createComponent("btb-marker-v4-test-002")
        val payload =
            """
            {
              "overriddenAttribute": "build.buildTools",
              "versionRange": "[12,)",
              "markerChildren": {
                "buildToolBeans": [
                  {"beanType": "oracleDatabase", "versionPattern": "[12,)"}
                ],
                "vcsEntries": [
                  {"vcsPath": "ssh://git@example/x.git"}
                ]
              }
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // V4-MKR-003: POST with beanType="cProduct" and edition non-null → 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
        "V4-MKR-003: POST field-override build.buildTools with cProduct bean and edition set → 400 " +
            "(edition is Oracle-only constraint)",
    )
    fun v4mkr003_nonOracleWithEdition_returns400() {
        val id = createComponent("btb-marker-v4-test-003")
        val payload =
            """
            {
              "overriddenAttribute": "build.buildTools",
              "versionRange": "[12,)",
              "markerChildren": {
                "buildToolBeans": [
                  {
                    "beanType": "cProduct",
                    "versionPattern": "[1,)",
                    "edition": "EE"
                  }
                ]
              }
            }
            """.trimIndent()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }
}
