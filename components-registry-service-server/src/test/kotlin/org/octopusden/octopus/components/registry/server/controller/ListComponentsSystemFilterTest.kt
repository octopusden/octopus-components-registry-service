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
 * Review finding #7: `ComponentManagementServiceImpl.buildSpecification` accepts a
 * `system` filter but the implementation returns the base spec unchanged — the
 * comment promises an in-memory post-filter but the code never filters. JPA
 * Criteria can't portably do `text[]` contains across H2 PG-compat and
 * PostgreSQL, and the endpoint has no native query yet. For now `?system=foo`
 * must be rejected with 400 so callers get a clear signal instead of a page of
 * unfiltered results that silently doesn't match their intent.
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
    @DisplayName("listComponents with filter.system must return 400 until native array-contains is implemented")
    fun listComponents_withSystemFilter_rejected() {
        mvc
            .perform(get("/rest/api/4/components").with(viewerJwt()).param("system", "ANYSYSTEM"))
            .andExpect(status().isBadRequest)
    }
}
