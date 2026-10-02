package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultMatcher
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-099: `POST /rest/api/4/components/{idOrName}/distribution/preview` end-to-end (ft-db), including
 * parity of the saved-state verdict with `GET /rest/api/2/components/{c}/versions/{v}/distribution`.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class DistributionPreviewApiTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(DistributionPreviewApiTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-099: 200 - candidate override set is evaluated per path (OVERRIDE / BASE / unevaluable)")
    fun `SYS-099 preview evaluates candidate overrides`() {
        val c = seed()
        val resp =
            preview(
                c.id,
                """{"version":"1.5","fieldOverrides":[""" +
                    """{"overriddenAttribute":"distribution.maven","versionRange":"[1.0, 2.0)","markerChildren":{"mavenArtifacts":[]}},""" +
                    """{"overriddenAttribute":"distribution.docker","versionRange":"garbage","markerChildren":{"dockerImages":[]}}]}""",
            )
        assertEquals("1.5", resp["version"].asText())
        assertEquals(true, resp["supported"].asBoolean())
        val maven = path(resp, "distribution.maven")
        assertEquals("OVERRIDE", maven["source"].asText())
        assertEquals(listOf("[1.0, 2.0)"), maven["matchedRanges"].map { it.asText() })
        val docker = path(resp, "distribution.docker")
        assertEquals("BASE", docker["source"].asText())
        assertEquals(listOf("garbage"), docker["unevaluableRanges"].map { it.asText() })
    }

    @Test
    @DisplayName("SYS-099: omitted fields use the saved state and agree with GET .../versions/{v}/distribution")
    fun `SYS-099 saved state agrees with distribution endpoint`() {
        val c = seed()
        // 1.5: inside coverage, inside the saved [1.0,2.0) maven override.
        val inOverride = preview(c.name, """{"version":"1.5"}""")
        assertEquals(true, inOverride["supported"].asBoolean())
        assertEquals("OVERRIDE", path(inOverride, "distribution.maven")["source"].asText())
        assertTrue(getGav(c.name, "1.5")!!.contains(c.overrideArtifact), "v2 GAV at 1.5 must be the override's")

        // 2.5: inside coverage, outside the override → BASE.
        val inBase = preview(c.name, """{"version":"2.5"}""")
        assertEquals(true, inBase["supported"].asBoolean())
        assertEquals("BASE", path(inBase, "distribution.maven")["source"].asText())
        assertTrue(getGav(c.name, "2.5")!!.contains(c.baseArtifact), "v2 GAV at 2.5 must be the base's")

        // 4.0: outside the saved [1.0,3.0) coverage → unsupported, and the v2 endpoint 404s.
        val outside = preview(c.name, """{"version":"4.0"}""")
        assertEquals(false, outside["supported"].asBoolean())
        assertEquals(0, outside["paths"].size())
        assertEquals(null, getGav(c.name, "4.0"))
    }

    @Test
    @DisplayName("SYS-099: candidate supportedVersions replace the saved coverage; an unparseable range counts as covering")
    fun `SYS-099 candidate coverage replaces saved coverage`() {
        val c = seed()
        assertEquals(true, preview(c.id, """{"version":"4.0","supportedVersions":{"all":true}}""")["supported"].asBoolean())
        assertEquals(
            true,
            preview(c.id, """{"version":"4.0","supportedVersions":{"all":false,"ranges":["nope"]}}""")["supported"].asBoolean(),
        )
        assertEquals(
            false,
            preview(c.id, """{"version":"1.5","supportedVersions":{"ranges":["[2.0,3.0)"]}}""")["supported"].asBoolean(),
        )
    }

    @Test
    @DisplayName("SYS-099: 400 for a non-numeric version, naming version")
    fun `SYS-099 non-numeric version is 400`() {
        val c = seed()
        val body =
            perform(c.id, """{"version":"abc"}""", adminJwt(), status().isBadRequest)
        assertTrue(objectMapper.readTree(body)["errorMessage"].asText().startsWith("version"), body)
        perform(c.id, """{"version":"   "}""", adminJwt(), status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-099: 400 for a body without version (malformed request body)")
    fun `SYS-099 missing version is 400`() {
        val c = seed()
        val body = perform(c.id, """{"supportedVersions":{"all":true}}""", adminJwt(), status().isBadRequest)
        assertEquals("Malformed request body", objectMapper.readTree(body)["errorMessage"].asText(), body)
    }

    @Test
    @DisplayName("SYS-099: 404 for an unknown component")
    fun `SYS-099 unknown component is 404`() {
        perform("no-such-component-${UUID.randomUUID()}", """{"version":"1.0"}""", adminJwt(), status().isNotFound)
        perform(UUID.randomUUID().toString(), """{"version":"1.0"}""", adminJwt(), status().isNotFound)
    }

    @Test
    @DisplayName("SYS-099: 401 when unauthenticated")
    fun `SYS-099 unauthenticated is 401`() {
        val c = seed()
        mvc
            .perform(
                post("/rest/api/4/components/${c.id}/distribution/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":"1.0"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("SYS-099: a caller with ACCESS_COMPONENTS but no edit rights may preview; without it - 403")
    fun `SYS-099 read permission is required and sufficient`() {
        val c = seed()
        perform(c.name, """{"version":"1.5"}""", viewerJwt(), status().isOk)
        val noPermissions =
            jwt()
                .jwt { it.claim("preferred_username", "nobody") }
                .authorities(SimpleGrantedAuthority("ROLE_UNMAPPED"))
        perform(c.id, """{"version":"1.5"}""", noPermissions, status().isForbidden)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private data class Seeded(
        val id: String,
        val name: String,
        val baseArtifact: String,
        val overrideArtifact: String,
    )

    /** Component with a base maven artifact, a distribution.maven override on [1.0,2.0) and coverage [1.0,3.0). */
    private fun seed(): Seeded {
        val s = UUID.randomUUID().toString().take(8)
        val name = "dp-$s"
        val baseArtifact = "dp-base-$s"
        val overrideArtifact = "dp-override-$s"
        val created =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","componentOwner":"owner1",""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"},""" +
                                """"mavenArtifacts":[{"groupPattern":"org.octopusden.octopus","artifactPattern":"$baseArtifact"}]}}""",
                        ),
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        val id = objectMapper.readTree(created)["id"].asText()
        mvc
            .perform(
                post("/rest/api/4/components/$id/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"versionRange":"[1.0,2.0)","overriddenAttribute":"distribution.maven",""" +
                            """"markerChildren":{"mavenArtifacts":[""" +
                            """{"groupPattern":"org.octopusden.octopus","artifactPattern":"$overrideArtifact"}]}}""",
                    ),
            ).andExpect(status().is2xxSuccessful)
        mvc
            .perform(
                put("/rest/api/4/components/$id/supported-versions")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ranges":["[1.0,3.0)"]}"""),
            ).andExpect(status().isOk)
        return Seeded(id, name, baseArtifact, overrideArtifact)
    }

    private fun perform(
        idOrName: String,
        body: String,
        auth: RequestPostProcessor,
        expected: ResultMatcher,
    ): String =
        mvc
            .perform(
                post("/rest/api/4/components/$idOrName/distribution/preview")
                    .with(auth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(expected)
            .andReturn()
            .response.contentAsString

    private fun preview(
        idOrName: String,
        body: String,
    ): JsonNode = objectMapper.readTree(perform(idOrName, body, adminJwt(), status().isOk))

    private fun path(
        resp: JsonNode,
        attribute: String,
    ): JsonNode = resp["paths"].single { it["attribute"].asText() == attribute }

    /** v2 per-version distribution GAV, or null when the endpoint 404s. */
    private fun getGav(
        name: String,
        version: String,
    ): String? {
        val response =
            mvc
                .perform(get("/rest/api/2/components/$name/versions/$version/distribution"))
                .andReturn()
                .response
        if (response.status == 404) return null
        assertEquals(200, response.status, response.contentAsString)
        return objectMapper.readTree(response.contentAsString)["GAV"]?.asText()
    }
}
