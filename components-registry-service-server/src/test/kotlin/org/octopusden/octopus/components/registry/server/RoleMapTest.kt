package org.octopusden.octopus.components.registry.server

import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.support.templateCreatorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths

/**
 * The template creator role as `GET /auth/me` reports it. The test profiles do not define that
 * role, so its answer proves the bundled role map is read; [BundledRoleMapTest] checks the
 * bundled entries themselves.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
@Tag("integration")
class RoleMapTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    init {
        val testResourcesPath = Paths.get(RoleMapTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun me(jwt: RequestPostProcessor) = mvc.perform(get("/auth/me").with(jwt)).andExpect(status().isOk)

    @Test
    @DisplayName("SYS-105: the template creator role carries ACCESS_COMPONENTS, ACCESS_AUDIT and CREATE_FROM_TEMPLATE (Decision 1)")
    fun `SYS-105 template creator role`() {
        me(templateCreatorJwt())
            .andExpect(jsonPath("$.roles[0].name").value("ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR"))
            .andExpect(
                jsonPath("$.roles[0].permissions", containsInAnyOrder("ACCESS_COMPONENTS", "ACCESS_AUDIT", "CREATE_FROM_TEMPLATE")),
            )
    }
}
