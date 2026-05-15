package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * Domain-named meta endpoints that expose the canonical option lists for the
 * three free-form string aspect fields that schema-v2 opened up (buildSystem,
 * repositoryType, generation). The Portal calls these to populate its
 * EnumSelect dropdowns when the admin field-config registry has no
 * options[] seeded for the matching field.
 *
 * Canonical-set choice — the persistence-layer enums (NOT the DTO enums)
 * are the source of truth, so the values the endpoint advertises are
 * exactly the values that round-trip through write/read via
 * `EntityMappers`:
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
 *     — what `Mappers.toDTO()` reads off the escrow model.
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

    init {
        val testResourcesPath =
            Paths.get(MetaOptionsEndpointsTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("GET /meta/build-systems returns canonical BuildSystem values")
    fun buildSystems_returnsCanonicalSet() {
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
    @DisplayName("GET /meta/repository-types returns canonical RepositoryType values")
    fun repositoryTypes_returnsCanonicalSet() {
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
    @DisplayName("GET /meta/build-systems uses persistence enum tokens (ESCROW_NOT_SUPPORTED, not NOT_SUPPORTED)")
    fun buildSystems_advertisesPersistenceTokens() {
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
    @DisplayName("GET /meta/escrow-generations returns canonical EscrowGenerationMode values")
    fun escrowGenerations_returnsCanonicalSet() {
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

}
