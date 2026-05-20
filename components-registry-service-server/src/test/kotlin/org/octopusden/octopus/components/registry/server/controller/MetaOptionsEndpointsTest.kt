package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
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
import java.nio.file.Paths
import java.util.UUID

/**
 * Domain-named meta endpoints that expose the canonical option lists for the
 * three free-form string aspect fields that schema-v2 opened up (buildSystem,
 * repositoryType, generation). The Portal calls these to populate its
 * EnumSelect dropdowns when the admin field-config registry has no
 * options[] seeded for the matching field.
 *
 * Canonical-set choice — for buildSystem and repositoryType the
 * persistence-layer enums (NOT the `core.dto.*` mirrors) are the source,
 * so the values the endpoint advertises are exactly the values that
 * round-trip through write/read via `EntityMappers`. The generation
 * enum has a single canonical source in the API module:
 *   - `org.octopusden.octopus.escrow.BuildSystem` — used by
 *     `EntityMappers.safeParseBuildSystem`. Differs from the
 *     `core.dto.BuildSystem` enum on a single token: persistence has
 *     `ESCROW_NOT_SUPPORTED`, DTO has `NOT_SUPPORTED`. Advertising the
 *     DTO token would silently drop user input on save because the
 *     mapper's `BuildSystem.valueOf(value)` would throw and
 *     `safeParseBuildSystem` returns null.
 *   - `org.octopusden.octopus.escrow.RepositoryType` — used by
 *     `EntityMappers.vcsRoot(...)`. Token set is identical to the DTO
 *     enum (GIT/MERCURIAL/CVS) but we still source from persistence
 *     so the choice is unambiguous.
 *   - `org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode`
 *     — the API-module enum that `Mappers.toDTO()` reads off the escrow
 *     model. The `core.dto.EscrowGenerationMode` mirror has the same
 *     token set; sourcing from the API enum keeps the mapping path
 *     direct.
 *
 * The endpoint names are domain-named (NOT `/meta/enums`) so the wire
 * surface does not pre-commit to "values come from a Java enum" — the
 * source is free to migrate to a config table or admin-editable registry
 * without breaking the contract.
 *
 * See Portal `frontend/src/hooks/useFieldOptions.ts` and Portal TD-005
 * item #10 for the call-site context.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class MetaOptionsEndpointsTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(MetaOptionsEndpointsTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-038: GET /meta/build-systems returns canonical BuildSystem values")
    fun `SYS-038 GET meta build-systems returns canonical BuildSystem values`() {
        val expected = BuildSystem.values().map { it.name }
        val result =
            mvc
                .perform(get("/rest/api/4/components/meta/build-systems").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$.length()").value(expected.size))
        expected.forEachIndexed { idx, value ->
            result.andExpect(jsonPath("$[$idx]").value(value))
        }
    }

    @Test
    @DisplayName("SYS-038: GET /meta/repository-types returns canonical RepositoryType values")
    fun `SYS-038 GET meta repository-types returns canonical RepositoryType values`() {
        val expected = RepositoryType.values().map { it.name }
        val result =
            mvc
                .perform(get("/rest/api/4/components/meta/repository-types").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$.length()").value(expected.size))
        expected.forEachIndexed { idx, value ->
            result.andExpect(jsonPath("$[$idx]").value(value))
        }
    }

    @Test
    @DisplayName("SYS-038: /meta/build-systems uses persistence enum tokens (ESCROW_NOT_SUPPORTED, not NOT_SUPPORTED)")
    fun `SYS-038 GET meta build-systems uses persistence enum tokens not DTO mirror`() {
        // Pins the choice of persistence-layer enum over the DTO enum. If a
        // future refactor accidentally reverts the controller to
        // `core.dto.BuildSystem`, this test catches it because that enum has
        // `NOT_SUPPORTED` (which would NOT round-trip through EntityMappers).
        mvc
            .perform(get("/rest/api/4/components/meta/build-systems").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasItem("ESCROW_NOT_SUPPORTED")))
            .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("NOT_SUPPORTED"))))
    }

    @Test
    @DisplayName("SYS-038: GET /meta/escrow-generations returns canonical EscrowGenerationMode values")
    fun `SYS-038 GET meta escrow-generations returns canonical EscrowGenerationMode values`() {
        val expected = EscrowGenerationMode.values().map { it.name }
        val result =
            mvc
                .perform(get("/rest/api/4/components/meta/escrow-generations").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$.length()").value(expected.size))
        expected.forEachIndexed { idx, value ->
            result.andExpect(jsonPath("$[$idx]").value(value))
        }
    }

    // /meta/labels is sourced from the `component_labels` junction (NOT the
    // master `labels` table) so the picker advertises only label codes that
    // are actually in use on at least one component — parity with
    // /meta/owners, which sources from components.componentOwner. A master
    // LabelEntity exists in the schema but may contain orphan codes that no
    // component carries; advertising those would create dead options in the
    // Portal picker.

    private fun uniqueLabel(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponentWithLabels(
        name: String,
        labels: Set<String>,
    ) {
        val labelsJson = labels.joinToString(",") { "\"$it\"" }
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"$name","displayName":"$name","labels":[$labelsJson]}"""),
            ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("SYS-040: GET /meta/labels returns sorted distinct label codes from the junction")
    fun `SYS-040 GET meta labels returns sorted distinct codes from junction`() {
        // Seed three components with overlapping labels. Distinct, sorted
        // ascending must be `alpha, beta, gamma` regardless of insertion
        // order. Using random suffixes keeps the assertion independent of
        // other tests in the same Spring context.
        val a = uniqueLabel("alpha")
        val b = uniqueLabel("beta")
        val g = uniqueLabel("gamma")

        createComponentWithLabels("meta_labels_one_${UUID.randomUUID().toString().take(6)}", setOf(b, a))
        createComponentWithLabels("meta_labels_two_${UUID.randomUUID().toString().take(6)}", setOf(g, a))
        createComponentWithLabels("meta_labels_three_${UUID.randomUUID().toString().take(6)}", setOf(b))

        val body =
            mvc
                .perform(get("/rest/api/4/components/meta/labels").with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andReturn()
                .response.contentAsString
        val all = objectMapper.readTree(body).map { it.asText() }
        val seeded = all.filter { it == a || it == b || it == g }
        assert(seeded == listOf(a, b, g).sorted()) {
            "expected seeded labels sorted ascending; got $seeded"
        }
        // No duplicates across the entire response (covers both seeded and
        // any pre-existing entries from earlier tests).
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
    }

    @Test
    @DisplayName("SYS-040: GET /meta/labels always returns 200 + JSON array (response shape)")
    fun `SYS-040 GET meta labels returns 200 array regardless of DB state`() {
        // Shape contract: always 200 + array, never 404. This case asserts
        // the shape against the seeded context inside this test class; the
        // dedicated empty-DB case (Portal's "no labels exist yet" deploy
        // window contract) lives in MetaLabelsEmptyDbContractTest, which
        // spins up a fresh context with zero component_labels rows to
        // verify the empty-array path specifically.
        mvc
            .perform(get("/rest/api/4/components/meta/labels").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }
}
