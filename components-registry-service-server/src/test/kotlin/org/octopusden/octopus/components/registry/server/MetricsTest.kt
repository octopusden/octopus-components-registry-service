package org.octopusden.octopus.components.registry.server

import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

@AutoConfigureMockMvc
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class]
)
@ActiveProfiles("common", "test")
class MetricsTest {

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun shouldReturnMetrics() {
        mvc.perform(
            MockMvcRequestBuilders.get(URI.create("/actuator/metrics/components.buildsystem.count"))
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.name").value("components.buildsystem.count"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.measurements[0].statistic").value("VALUE"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.measurements[0].value").value(50.0))
            .andExpect(MockMvcResultMatchers.jsonPath("$.availableTags[0].tag").value("buildSystem"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.availableTags[0].values").isArray)
            .andExpect(
                MockMvcResultMatchers.jsonPath("$.availableTags[0].values").value(
                    containsInAnyOrder(
                        "MAVEN",
                        "NOT_SUPPORTED",
                        "WHISKEY",
                        "GOLANG",
                        "BS2_0",
                        "PROVIDED",
                        "ESCROW_PROVIDED_MANUALLY",
                        "ECLIPSE_MAVEN",
                        "GRADLE",
                        "IN_CONTAINER"
                    )
                )
            )
    }

    companion object {
        @JvmStatic
        fun getTestResourcesPath(): Path =
            Paths.get(MetricsTest::class.java.getResource("/expected-data")!!.toURI()).parent

        @BeforeAll
        @JvmStatic
        fun configureSpringAppTestDataDir() {
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", getTestResourcesPath().toString())
        }
    }
}