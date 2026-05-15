package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * Closes the open #192 review findings about v4 write-side validation: enum-typed
 * fields (`productType`, `buildSystem`, `repositoryType`, `packageType`) and
 * `versionRange` syntax on PATCH used to be accepted verbatim, with the resolver
 * silently dropping invalid values or, in the case of malformed ranges, breaking
 * the enumeration on the read path.
 *
 * Each test sends one specific bad value and asserts 400. Pre-fix, all of these
 * either persisted the bad value (and would later surface as a 500 on read) or
 * silently succeeded.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class V4WriteValidationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(V4WriteValidationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun postCreate(body: String) =
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )

    @Test
    @DisplayName("CREATE rejects unknown productType")
    fun create_rejects_unknownProductType() {
        val body = """{"name": "validation-test-comp-pt", "productType": "NOT_A_REAL_PRODUCT_TYPE"}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown baseConfiguration.build.buildSystem")
    fun create_rejects_unknownBuildSystem() {
        val body =
            """{"name": "validation-test-comp-bs", "baseConfiguration": {"build": {"buildSystem": "NOT_A_BUILD_SYSTEM"}}}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown vcsEntry.repositoryType")
    fun create_rejects_unknownRepositoryType() {
        val body =
            """
            {
              "name": "validation-test-comp-rt",
              "baseConfiguration": {
                "vcsEntries": [
                  {"vcsPath": "ssh://git@example/x.git", "repositoryType": "NOT_A_REPO_TYPE"}
                ]
              }
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("CREATE rejects unknown packages[].packageType")
    fun create_rejects_unknownPackageType() {
        val body =
            """
            {
              "name": "validation-test-comp-pkg",
              "baseConfiguration": {
                "packages": [
                  {"packageType": "TARBALL", "packageName": "some-name"}
                ]
              }
            }
            """.trimIndent()
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("POST /field-overrides rejects scalar build.buildSystem with unknown enum value")
    fun fieldOverride_rejects_unknownBuildSystemScalar() {
        // Seed a minimal valid component first.
        val createBody = """{"name": "validation-test-comp-bs-fo", "baseConfiguration": {}}"""
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "build.buildSystem",
              "versionRange": "[99.0.0,)",
              "value": "NOT_A_BUILD_SYSTEM"
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

    @Test
    @DisplayName(
        "field-override marker `vcs.settings` rejects unknown vcsEntries[].repositoryType",
    )
    fun fieldOverride_rejects_unknownRepositoryTypeInMarker() {
        val createBody = """{"name": "validation-test-comp-rt-fo"}"""
        val seedResponse =
            postCreate(createBody).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "vcs.settings",
              "versionRange": "[99.0.0,)",
              "markerChildren": {
                "vcsEntries": [
                  {"vcsPath": "ssh://git@example/x.git", "repositoryType": "NOT_A_REPO_TYPE"}
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

    @Test
    @DisplayName(
        "field-override marker `distribution.packages` rejects unknown packages[].packageType",
    )
    fun fieldOverride_rejects_unknownPackageTypeInMarker() {
        val createBody = """{"name": "validation-test-comp-pkg-fo"}"""
        val seedResponse =
            postCreate(createBody).andExpect(status().is2xxSuccessful).andReturn().response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "distribution.packages",
              "versionRange": "[99.0.0,)",
              "markerChildren": {
                "packages": [
                  {"packageType": "TARBALL", "packageName": "some-name"}
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

    @Test
    @DisplayName("CREATE rejects unknown baseConfiguration.escrow.generation")
    fun create_rejects_unknownEscrowGeneration() {
        val body =
            """{"name": "validation-test-comp-gen", "baseConfiguration": {"escrow": {"generation": "NOT_A_GENERATION_MODE"}}}"""
        postCreate(body).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("POST /field-overrides rejects scalar escrow.generation with unknown enum value")
    fun fieldOverride_rejects_unknownEscrowGenerationScalar() {
        val createBody = """{"name": "validation-test-comp-gen-fo", "baseConfiguration": {}}"""
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(seedResponse)["id"].asText()

        val payload =
            """
            {
              "overriddenAttribute": "escrow.generation",
              "versionRange": "[99.0.0,)",
              "value": "NOT_A_GENERATION_MODE"
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

    @Test
    @DisplayName("PATCH baseConfiguration.versionRange with bad syntax returns 400 (pre-existing)")
    fun patch_rejects_invalidVersionRangeSyntax() {
        // Seed a minimal valid component first.
        val createBody = """{"name": "validation-test-comp-vr"}"""
        val seedResponse =
            postCreate(createBody)
                .andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val seed = objectMapper.readTree(seedResponse)
        val id = seed["id"].asText()
        val versionLock = seed["version"].asLong()
        val patchBody =
            """{"version": $versionLock, "baseConfiguration": {"versionRange": "this-is-not-a-version-range"}}"""
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody),
            ).andExpect(status().isBadRequest)
    }
}
