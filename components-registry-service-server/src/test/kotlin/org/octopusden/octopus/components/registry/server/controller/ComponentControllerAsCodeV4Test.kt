package org.octopusden.octopus.components.registry.server.controller

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.RenderedComponentCode
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * GET /rest/api/4/components/{idOrName}/as-code — the Groovy "as-code" view.
 * The rendering itself is covered by `ComponentCodeRendererTest`; here we pin
 * the HTTP contract: media type, the version-driven FULL↔RESOLVED routing, the
 * `Content-Disposition` filename (canonical key even when called by UUID), and
 * the 404 propagation.
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
class ComponentControllerAsCodeV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @MockBean
    private lateinit var componentManagementService: ComponentManagementService

    init {
        val testResourcesPath =
            Paths.get(ComponentControllerAsCodeV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("FULL: text/plain body + Content-Disposition filename from canonical key")
    fun fullView() {
        `when`(componentManagementService.renderComponentAsCode("bcomponent"))
            .thenReturn(RenderedComponentCode("bcomponent", "bcomponent {\n}\n"))

        mvc
            .perform(get("/rest/api/4/components/bcomponent/as-code").with(editorJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("bcomponent {")))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("bcomponent.groovy")))

        verify(componentManagementService, never()).renderResolvedComponentAsCode("bcomponent", "")
    }

    @Test
    @DisplayName("RESOLVED: ?version routes to the resolved renderer")
    fun resolvedView() {
        `when`(componentManagementService.renderResolvedComponentAsCode("bcomponent", "2.0"))
            .thenReturn(RenderedComponentCode("bcomponent", "bcomponent {\n    // resolved 2.0\n}\n"))

        mvc
            .perform(get("/rest/api/4/components/bcomponent/as-code?version=2.0").with(editorJwt()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("resolved 2.0")))

        verify(componentManagementService).renderResolvedComponentAsCode("bcomponent", "2.0")
        verify(componentManagementService, never()).renderComponentAsCode("bcomponent")
    }

    @Test
    @DisplayName("Filename uses canonical key even when the path is a UUID")
    fun filenameFromCanonicalKey() {
        val uuid = UUID.randomUUID()
        `when`(componentManagementService.renderComponentAsCode(uuid.toString()))
            .thenReturn(RenderedComponentCode("real-key", "real-key {\n}\n"))

        mvc
            .perform(get("/rest/api/4/components/$uuid/as-code").with(editorJwt()))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("real-key.groovy")))
    }

    @Test
    @DisplayName("Unknown component → 404")
    fun unknownComponent404() {
        `when`(componentManagementService.renderComponentAsCode("ghost"))
            .thenThrow(NotFoundException("Component 'ghost' not found"))

        mvc
            .perform(get("/rest/api/4/components/ghost/as-code").with(editorJwt()))
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("Version that resolves to nothing → 404")
    fun versionNotResolvable404() {
        `when`(componentManagementService.renderResolvedComponentAsCode("bcomponent", "99.0"))
            .thenThrow(NotFoundException("No configuration resolves for component 'bcomponent' at version '99.0'"))

        mvc
            .perform(get("/rest/api/4/components/bcomponent/as-code?version=99.0").with(editorJwt()))
            .andExpect(status().isNotFound)
    }
}
