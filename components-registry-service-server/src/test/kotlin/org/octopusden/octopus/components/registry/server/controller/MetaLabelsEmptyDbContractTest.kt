package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
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

/**
 * SYS-040 contract — empty-DB case for GET /rest/api/4/components/meta/labels.
 *
 * The endpoint must return 200 + an empty JSON array when no rows exist in
 * the component_labels junction. NOT 404, NOT a 5xx — Portal's useLabels
 * hook has a 404/501 fallback for the transitional pre-deploy window, but
 * steady state must hit the happy path. Verifying this requires a Spring
 * context with zero junction rows, which is awkward inside the seeded
 * MetaOptionsEndpointsTest (other test methods there create components
 * with labels in the shared H2). This class isolates the empty case in a
 * dedicated context that wipes component_labels in @BeforeEach so the
 * endpoint genuinely sees zero rows.
 *
 * Cost: one extra Spring Boot bootstrap (~30s in CI). The signal — that
 * the empty-DB happy path is 200 + [] and not 404 — justifies the cost.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class MetaLabelsEmptyDbContractTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var componentLabelRepository: ComponentLabelRepository

    @BeforeEach
    fun clearLabels() {
        // ft-db's auto-migrate seeds components (and their labels) at
        // startup. Wipe the junction so the endpoint genuinely sees an
        // empty distinct-labels set. We don't touch the components table
        // itself; only the junction matters for /meta/labels.
        componentLabelRepository.deleteAll()
    }

    @Test
    @DisplayName("SYS-040: GET /meta/labels with zero junction rows returns 200 + [] (NOT 404)")
    fun `SYS-040 GET meta labels empty DB returns 200 and empty array`() {
        mvc
            .perform(get("/rest/api/4/components/meta/labels").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$.length()").value(0))
    }
}
