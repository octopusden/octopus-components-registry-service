package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * Schema v2: `system` was a `text[]` column on the legacy schema, which JPA
 * Criteria couldn't filter portably across H2 PG-compat and Postgres, so the
 * v1 implementation rejected `?system=…` with 400. With the v2 normalised
 * `component_systems` junction table the filter is expressible as a plain
 * JOIN (see `ComponentManagementServiceImpl.buildSpecification`), so the
 * endpoint now returns 200 and a filtered (possibly empty) page.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsSystemFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsSystemFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("listComponents without filter.system returns 200")
    fun listComponents_noSystemFilter_ok() {
        mvc.perform(get("/rest/api/4/components").with(viewerJwt())).andExpect(status().isOk)
    }

    /**
     * Behaviour, not just status: when the auto-migrated fixture contains
     * components with `system = "CLASSIC"` (see common/TestComponents.groovy),
     * filtering by `?system=CLASSIC` must return a non-empty content list AND
     * every returned row must declare CLASSIC among its systems. A weaker
     * "expect 200" check would still pass if the new junction-based filter
     * were silently ignored and the endpoint returned an unfiltered page.
     */
    @Test
    @DisplayName("?system=CLASSIC returns only components whose systems include CLASSIC")
    fun systemFilter_includesMatchingComponents() {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(viewerJwt()).param("system", "CLASSIC").param("size", "200"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val content = objectMapper.readTree(body).path("content")
        assertTrue(content.isArray && content.size() > 0, "Expected at least one CLASSIC component; got: ${body.take(400)}")
        for (component in content) {
            val systems = component.path("systems").map { it.asText() }
            assertTrue(
                systems.contains("CLASSIC"),
                "Component '${component.path("name").asText()}' returned by ?system=CLASSIC must declare CLASSIC; got systems=$systems",
            )
        }
    }

    @Test
    @DisplayName("?system=<unknown> returns empty content (not an unfiltered page)")
    fun systemFilter_excludesNonMatchingComponents() {
        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("system", "DEFINITELY_NOT_A_REAL_SYSTEM_xyz123")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val content = objectMapper.readTree(body).path("content")
        assertFalse(
            content.isArray && content.size() > 0,
            "?system=<unknown> must return an empty content array; got ${content.size()} entries: ${body.take(400)}",
        )
    }
}
