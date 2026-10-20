package org.octopusden.octopus.components.registry.server

import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
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
 * The permissions each registry role carries, as `GET /auth/me` reports them. The template
 * creator role is defined only in the bundled role map, so its answer proves that map is read.
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
    @DisplayName("SYS-103: the template creator role carries ACCESS_COMPONENTS, ACCESS_AUDIT and CREATE_FROM_TEMPLATE (Decision 1)")
    fun templateCreatorRole() {
        me(templateCreatorJwt())
            .andExpect(jsonPath("$.roles[0].name").value("ROLE_COMPONENTS_REGISTRY_TEMPLATE_CREATOR"))
            .andExpect(
                jsonPath("$.roles[0].permissions", containsInAnyOrder("ACCESS_COMPONENTS", "ACCESS_AUDIT", "CREATE_FROM_TEMPLATE")),
            )
    }

    @Test
    @DisplayName("SYS-103: the editor role keeps its permissions, without CREATE_FROM_TEMPLATE (Decision 2)")
    fun editorRoleUnchanged() {
        me(editorJwt())
            .andExpect(jsonPath("$.roles[0].permissions", containsInAnyOrder("ACCESS_COMPONENTS", "CREATE_COMPONENTS", "ACCESS_AUDIT")))
    }

    @Test
    @DisplayName("SYS-103: the admin role keeps its permissions, without CREATE_FROM_TEMPLATE (Decision 2)")
    fun adminRoleUnchanged() {
        me(adminJwt())
            .andExpect(
                jsonPath(
                    "$.roles[0].permissions",
                    containsInAnyOrder(
                        "ACCESS_COMPONENTS",
                        "CREATE_COMPONENTS",
                        "EDIT_ANY_COMPONENT",
                        "ARCHIVE_COMPONENTS",
                        "RENAME_COMPONENTS",
                        "DELETE_COMPONENTS",
                        "IMPORT_DATA",
                        "ACCESS_AUDIT",
                        "EDIT_METADATA",
                    ),
                ),
            )
    }
}
