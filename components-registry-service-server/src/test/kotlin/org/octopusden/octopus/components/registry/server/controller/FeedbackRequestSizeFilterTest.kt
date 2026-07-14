package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * SYS-062: the feedback body-size guard (FeedbackRequestSizeFilter) rejects an
 * oversized submission with 413 before the controller runs. The cap is lowered to a
 * tiny value here so a normal JSON body trips it.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@TestPropertySource(properties = ["components-registry.feedback.max-request-bytes=200"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class FeedbackRequestSizeFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    init {
        val testResourcesPath =
            java.nio.file.Paths
                .get(FeedbackRequestSizeFilterTest::class.java.getResource("/expected-data")!!.toURI())
                .parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("SYS-062 oversized feedback body is rejected with 413")
    fun `SYS-062 body over cap is rejected`() {
        val bigMessage = "x".repeat(500)
        mvc
            .perform(
                post("/rest/api/4/feedback")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"type":"BUG","message":"$bigMessage"}"""),
            ).andExpect(status().isPayloadTooLarge)
    }
}
