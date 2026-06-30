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
 * PR-3b — supported-versions (coverage) edit API (ADR-018, decoupled redesign). Drives the real
 * `GET` / `PUT /rest/api/4/components/{id}/supported-versions` end-to-end (ft-db = H2 + auto-migrate)
 * and asserts the declarative replace, the contiguous/overlapping merge into clean coverage (coverage
 * is stored MERGED and is independent of per-attribute overrides — no auto-split), the ALL reset, and
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
    @DisplayName("PUT replaces coverage; contiguous ranges merge; GET reflects it; PUT {all:true} clears back to ALL")
    fun `put replaces and clears coverage`() {
        val id = createComponent("sv_put_${UUID.randomUUID().toString().take(8)}")

        // Two contiguous ranges are stored MERGED — coverage is a clean maximal-contiguous union.
        putSupported(id, """{"ranges":["[1.0,2.0)","[2.0,)"]}""")
        val after = getSupported(id)
        assertEquals(false, after.path("all").asBoolean())
        assertEquals(listOf("[1.0,)"), after.path("ranges").map { it.asText() })

        putSupported(id, """{"all":true}""")
        val cleared = getSupported(id)
        assertEquals(true, cleared.path("all").asBoolean())
        assertEquals(0, cleared.path("ranges").size())
    }

    @Test
    @DisplayName("coverage is decoupled from overrides — PUT supported is NOT split by an existing override's edges")
    fun `put coverage is independent of overrides`() {
        val id = createComponent("sv_align_${UUID.randomUUID().toString().take(8)}")
        // Override first (component is all-versions, so this is a free-standing override view).
        createFieldOverride(id, """{"overriddenAttribute":"build.javaVersion","versionRange":"[2.0,3.0)","value":"11"}""")

        // Declare supported = [1.0,10.0): under the decoupled model coverage is stored verbatim-merged,
        // NOT auto-split by the [2.0,3.0) override. Enumeration (range views) does the splitting at READ.
        putSupported(id, """{"ranges":["[1.0,10.0)"]}""")
        assertEquals(
            listOf("[1.0,10.0)"),
            getSupported(id).path("ranges").map { it.asText() },
            "PUT supported must store merged coverage unchanged — overrides do not reshape coverage",
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

    @Test
    @DisplayName("idempotent re-PUT of the same coverage set is a no-op (no unique-index 500)")
    fun `idempotent re-put is safe`() {
        val id = createComponent("sv_idem_${UUID.randomUUID().toString().take(8)}")
        putSupported(id, """{"ranges":["[1.0,2.0)","[2.0,)"]}""")
        // Re-PUT the identical set — the delta replace must add/delete nothing, not violate the
        // partial unique index by re-inserting a row whose range already exists. The two contiguous
        // ranges merge to a single [1.0,) coverage row on both PUTs.
        val second = putSupported(id, """{"ranges":["[1.0,2.0)","[2.0,)"]}""")
        assertEquals(listOf("[1.0,)"), second.path("ranges").map { it.asText() })
    }

    @Test
    @DisplayName("PUT of ranges that TILE all-versions collapses to supported = ALL (no spurious all-versions row)")
    fun `tiling ranges collapse to all`() {
        val id = createComponent("sv_tile_${UUID.randomUUID().toString().take(8)}")
        // An override that would be "outside" a bounded supported set but is INSIDE all-versions — used
        // to assert the tiling-collapse path treats supported as ALL (no spurious V1/V5 warning).
        createFieldOverride(id, """{"overriddenAttribute":"build.javaVersion","versionRange":"[5.0,6.0)","value":"11"}""")
        // Each individual range is bounded (passes per-range validation), but together they tile every
        // version → mergeUnion collapses to the all-versions sentinel. The canonical invariant is
        // "supported = ALL ⟺ no RANGE_PRESENCE rows", so this must report all=true with zero ranges,
        // NOT all=false with a bounded-looking all-versions coverage row.
        val resp = putSupported(id, """{"ranges":["(,2.0)","[2.0,)"]}""")
        assertEquals(true, resp.path("all").asBoolean())
        assertEquals(0, resp.path("ranges").size())
        // Tiling collapsed to ALL → the [5.0,6.0) override is inside supported → NO spurious warning.
        assertEquals(0, resp.path("warnings").size(), "tiling-collapse to ALL must not warn that an override is outside supported")
        // GET reflects the same.
        val after = getSupported(id)
        assertEquals(true, after.path("all").asBoolean())
        assertEquals(0, after.path("ranges").size())
    }

    @Test
    @DisplayName("PUT merges overlapping supported ranges into a clean union (no disjoint requirement)")
    fun `overlapping ranges merged`() {
        val id = createComponent("sv_ovl_${UUID.randomUUID().toString().take(8)}")
        // Overlapping ranges are not rejected — coverage is stored as a maximal-contiguous union.
        val resp = putSupported(id, """{"ranges":["[1.0,3.0)","[2.0,4.0)"]}""")
        assertEquals(listOf("[1.0,4.0)"), resp.path("ranges").map { it.asText() })
    }

    @Test
    @DisplayName("PUT rejects an all-versions sentinel as a coverage range (use all:true instead)")
    fun `all versions sentinel rejected`() {
        val id = createComponent("sv_sent_${UUID.randomUUID().toString().take(8)}")
        putSupportedExpectingStatus(id, """{"ranges":["(,0),[0,)"]}""", status().isBadRequest)
        putSupportedExpectingStatus(id, """{"ranges":["(,)"]}""", status().isBadRequest)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun putSupportedExpectingStatus(
        componentId: String,
        payload: String,
        matcher: org.springframework.test.web.servlet.ResultMatcher,
    ) {
        mvc
            .perform(
                put("/rest/api/4/components/$componentId/supported-versions")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(matcher)
    }

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
