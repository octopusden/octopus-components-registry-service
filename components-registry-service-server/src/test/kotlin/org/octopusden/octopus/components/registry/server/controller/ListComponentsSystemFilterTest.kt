package org.octopusden.octopus.components.registry.server.controller

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

    @Test
    @DisplayName("listComponents with filter.system returns 200 (v2 junction-based filter)")
    fun listComponents_withSystemFilter_ok() {
        mvc
            .perform(get("/rest/api/4/components").with(viewerJwt()).param("system", "ANYSYSTEM"))
            .andExpect(status().isOk)
    }
}
