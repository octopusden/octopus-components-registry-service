package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Paths
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * PR-3b — supported-versions (coverage) edit API (ADR-018). Drives the real `GET` / `PUT
 * /rest/api/4/components/{id}/supported-versions` end-to-end (ft-db = H2 + auto-migrate) and asserts
 * the declarative replace, the auto-split re-alignment with an existing override, the ALL reset, and
 * the V1/V5 warn-and-allow advisory.
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
class SupportedVersionsApiTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(SupportedVersionsApiTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("a freshly-created component reports supported = ALL (no bounded coverage)")
    fun `fresh component is all-versions`() {
        val id = createComponent("sv_all_${UUID.randomUUID().toString().take(8)}")
        val resp = getSupported(id)
        assertEquals(true, resp.path("all").asBoolean())
        assertEquals(0, resp.path("ranges").size())
    }

    @Test
    @DisplayName("PUT replaces coverage; GET reflects it; PUT {all:true} clears back to ALL")
    fun `put replaces and clears coverage`() {
        val id = createComponent("sv_put_${UUID.randomUUID().toString().take(8)}")

        putSupported(id, """{"ranges":["[1.0,2.0)","[2.0,)"]}""")
        val after = getSupported(id)
        assertEquals(false, after.path("all").asBoolean())
        assertEquals(listOf("[1.0,2.0)", "[2.0,)"), after.path("ranges").map { it.asText() })

        putSupported(id, """{"all":true}""")
        val cleared = getSupported(id)
        assertEquals(true, cleared.path("all").asBoolean())
        assertEquals(0, cleared.path("ranges").size())
    }

    @Test
    @DisplayName("setting supported re-aligns an existing override via auto-split")
    fun `put re-aligns existing override`() {
        val id = createComponent("sv_align_${UUID.randomUUID().toString().take(8)}")
        // Override first (component is all-versions, so this is a free-standing override view).
        createFieldOverride(id, """{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,3.0)","value":"11"}""")

        // Now declare supported = [1.0,10.0): the override edge 2.0/3.0 falls inside → auto-split.
        putSupported(id, """{"ranges":["[1.0,10.0)"]}""")
        assertEquals(
            listOf("[1.0,2.0)", "[2.0,3.0)", "[3.0,10.0)"),
            getSupported(id).path("ranges").map { it.asText() },
            "PUT supported must re-align coverage to the existing override's edges",
        )
    }

    @Test
    @DisplayName("V1/V5: an override left outside the new supported set produces a non-blocking warning")
    fun `override outside supported warns`() {
        val id = createComponent("sv_warn_${UUID.randomUUID().toString().take(8)}")
        createFieldOverride(id, """{"overriddenAttribute":"build.javaVersion","versionRange":"[5.0,6.0)","value":"11"}""")

        val resp = putSupported(id, """{"ranges":["[1.0,2.0)"]}""")
        val warnings = resp.path("warnings").map { it.asText() }
        assertTrue(
            warnings.any { it.contains("[5.0,6.0)") },
            "expected a V1/V5 warning that the [5.0,6.0) override is outside supported; got: $warnings",
        )
        // Still applied (warn-and-allow): supported is the requested set.
        assertEquals(listOf("[1.0,2.0)"), resp.path("ranges").map { it.asText() })
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createComponent(name: String): String {
        val body =
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
                ).andExpect(status().is2xxSuccessful)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun createFieldOverride(
        componentId: String,
        payload: String,
    ) {
        mvc
            .perform(
                post("/rest/api/4/components/$componentId/field-overrides")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().is2xxSuccessful)
    }

    private fun getSupported(componentId: String) =
        objectMapper.readTree(
            mvc
                .perform(get("/rest/api/4/components/$componentId/supported-versions").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun putSupported(
        componentId: String,
        payload: String,
    ) = objectMapper.readTree(
        mvc
            .perform(
                put("/rest/api/4/components/$componentId/supported-versions")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isOk)
            .andReturn()
            .response.contentAsString,
    )
}
