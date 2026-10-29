package org.octopusden.octopus.components.registry.server.controller

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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * SYS-066 — with the feature flag UNSET (the default), the split controller bean is not registered,
 * so its path is unmapped (404). This is the structural "our DB only" guarantee: colleagues'
 * deployments never expose the one-off endpoint.
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
class CompositeOverrideSplitDisabledTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired private lateinit var mvc: MockMvc

    init {
        val testResourcesPath =
            Paths.get(CompositeOverrideSplitDisabledTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-066: with the flag unset the split endpoint is not wired (404)")
    fun `SYS-066 endpoint absent when flag off`() {
        mvc
            .perform(post("/rest/api/4/admin/field-overrides/split-composites?dryRun=true").with(adminJwt()))
            .andExpect(status().isNotFound)
    }
}
