package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * SYS-033: `/rest/api/4/info` exposes the build name and version anonymously
 * so the portal footer can render "Components Registry by F1 team
 * (portal X · service Y)" before the user has authenticated. Both layers of
 * the security chain (URL matcher in WebSecurityConfig + class-level
 * @PreAuthorize on other v4 controllers) are exercised through the full
 * SpringBootTest context — `@WebMvcTest` would not load the real
 * WebSecurityConfig and would silently pass even if the endpoint were
 * implicitly authenticated.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@Import(InfoControllerV4Test.TestBuildPropertiesConfig::class)
@ActiveProfiles("common", "test")
class InfoControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("SYS-033: GET /rest/api/4/info returns 200 with name and version, anonymous access")
    fun `SYS-033 anonymous GET info returns build name and version`() {
        mvc
            .perform(get("/rest/api/4/info"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.name").value(EXPECTED_NAME))
            .andExpect(jsonPath("$.version").value(EXPECTED_VERSION))
    }

    @TestConfiguration
    class TestBuildPropertiesConfig {
        // BuildProperties is a final class but has a public Properties-based
        // constructor — preferred over a Mockito mock (which would need
        // mockito-inline) and over relying on the production
        // META-INF/build-info.properties (which only exists in the bootJar,
        // not on the test classpath).
        @Bean
        fun buildProperties(): BuildProperties {
            val props = Properties()
            props.setProperty("name", EXPECTED_NAME)
            props.setProperty("version", EXPECTED_VERSION)
            return BuildProperties(props)
        }
    }

    companion object {
        private const val EXPECTED_NAME = "components-registry-service"
        private const val EXPECTED_VERSION = "3.0.42"

        // application-common.yml resolves work-dir from this property; without
        // it set the @SpringBootTest context fails to start with a
        // PropertyPlaceholderHelper IllegalArgumentException long before the
        // controller is even reached. Pattern lifted verbatim from MetricsTest.
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(InfoControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
