package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.FullMigrationResult
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths

// MIG-024: pin both gates that protect POST /rest/api/4/admin/migrate:
//   - URL-level requestMatchers("/rest/api/4/**").authenticated() in
//     WebSecurityConfig → 401 for anonymous callers.
//   - Class-level @PreAuthorize("@permissionEvaluator.canImport()") on
//     AdminControllerV4 → 403 for authenticated callers without IMPORT_DATA.
//
// ImportService is mocked so the positive path does not actually run a
// migration in the test context. Slice tests (@WebMvcTest) wouldn't load
// the real WebSecurityConfig, so the only meaningful regression test is
// a full @SpringBootTest.
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
class AdminControllerV4SecurityTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var importService: ImportService

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("MIG-024: POST /admin/migrate without JWT returns 401 and does not invoke ImportService")
    fun `MIG-024 anonymous POST migrate returns 401 and does not run migration`() {
        mvc
            .perform(post("/rest/api/4/admin/migrate"))
            .andExpect(status().isUnauthorized)

        verify(importService, never()).migrate()
    }

    @Test
    @DisplayName("MIG-024: POST /admin/migrate with non-IMPORT_DATA JWT returns 403 and does not invoke ImportService")
    fun `MIG-024 editor JWT POST migrate returns 403 and does not run migration`() {
        mvc
            .perform(post("/rest/api/4/admin/migrate").with(editorJwt()))
            .andExpect(status().isForbidden)

        verify(importService, never()).migrate()
    }

    @Test
    @DisplayName("MIG-024: POST /admin/migrate with IMPORT_DATA JWT returns 200 and invokes ImportService.migrate exactly once")
    fun `MIG-024 admin JWT POST migrate returns 200 and runs migration once`() {
        `when`(importService.migrate()).thenReturn(EMPTY_MIGRATION_RESULT)

        mvc
            .perform(post("/rest/api/4/admin/migrate").with(adminJwt()))
            .andExpect(status().isOk)

        verify(importService, times(1)).migrate()
    }

    companion object {
        private val EMPTY_MIGRATION_RESULT =
            FullMigrationResult(
                defaults = emptyMap(),
                components = BatchMigrationResult(total = 0, migrated = 0, failed = 0, skipped = 0, results = emptyList()),
            )

        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(AdminControllerV4SecurityTest::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
