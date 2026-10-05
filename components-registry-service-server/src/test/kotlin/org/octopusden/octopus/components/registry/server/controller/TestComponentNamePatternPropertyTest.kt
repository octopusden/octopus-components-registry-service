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
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/** SYS-099: an installation replaces the test-component name patterns through configuration. */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
    properties = ["components-registry.test-components.name-patterns=^lab-"],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class TestComponentNamePatternPropertyTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    init {
        val testResourcesPath =
            Paths.get(TestComponentNamePatternPropertyTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun create(name: String) =
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"$name","componentOwner":"owner1","testComponent":true,""" +
                        """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                ),
        )

    @Test
    @DisplayName("SYS-099: a configured pattern replaces the defaults for writes and the meta endpoint")
    fun `SYS-099 configured pattern replaces the defaults`() {
        val suffix = UUID.randomUUID().toString().take(8)
        create("lab-sys099-$suffix").andExpect(status().isCreated)
        create("test-sys099-$suffix").andExpect(status().isBadRequest)

        mvc
            .perform(get("/rest/api/4/components/meta/test-component-name-patterns").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(content().json("""["^lab-"]""", true))
    }
}
