package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.support.editorJwt
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
import java.util.UUID

/**
 * SYS-028 fallback narrowness — the polymorphic GET /{idOrName} must fall back
 * to the name lookup ONLY when the UUID lookup raises a "not found" (the
 * sentinel signalling "this id isn't in the DB"). Any other exception — DB
 * outage, connection pool exhausted, unexpected NPE in the service layer —
 * must propagate to the caller as a real server error so the failure is
 * visible, not laundered into a 404-by-name or a mis-targeted name lookup.
 *
 * A dedicated @SpringBootTest context is required because `@MockBean
 * ComponentManagementService` replaces the real bean for the whole context;
 * dropping it into `ComponentRenameTest` would break the nine other tests
 * that exercise the real service end-to-end.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ComponentControllerV4LookupFallbackTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @MockBean
    private lateinit var componentManagementService: ComponentManagementService

    init {
        val testResourcesPath =
            Paths.get(ComponentControllerV4LookupFallbackTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("GET /{idOrName}: UUID-path infra failure (non-NotFound) must surface, not silently re-route to name lookup")
    fun uuidPath_nonNotFoundException_propagatesWithoutFallback() {
        val uuid = UUID.randomUUID()
        `when`(componentManagementService.getComponent(uuid))
            .thenThrow(IllegalStateException("simulated db failure"))

        mvc
            .perform(get("/rest/api/4/components/$uuid").with(editorJwt()))
            .andExpect(status().is5xxServerError)

        // The fallback must NOT have fired — otherwise DB-down silently
        // becomes "component not found by name".
        verify(componentManagementService, never()).getComponentByName(uuid.toString())
    }

    @Test
    @DisplayName("GET /{idOrName}: UUID-path NotFoundException still falls through to name lookup")
    fun uuidPath_notFoundException_fallsBackToNameLookup() {
        val uuid = UUID.randomUUID()
        `when`(componentManagementService.getComponent(uuid))
            .thenThrow(NotFoundException("id not found"))
        `when`(componentManagementService.getComponentByName(uuid.toString()))
            .thenThrow(NotFoundException("name not found either"))

        // Outer result here is 404 because the stubbed name lookup also throws,
        // but the important assertion is that the name path was reached.
        mvc
            .perform(get("/rest/api/4/components/$uuid").with(editorJwt()))
            .andExpect(status().isNotFound)

        verify(componentManagementService).getComponentByName(uuid.toString())
    }
}
