package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.MigrationLifecycleGate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.nio.file.Paths

// Pins the transitional, ANONYMOUS migration-status probe (GET /rest/api/4/migration-status):
//   - permitAll URL rule in WebSecurityConfig → 200 (not 401) for a tokenless caller.
//     This is the whole point — the portal's validation sweep calls CRS without a JWT
//     and must be able to read this signal (the admin /migrate/job endpoint cannot).
//   - body maps MigrationLifecycleGate.current() → { running, kind }.
// Exercised through the full security stack (a slice @WebMvcTest wouldn't load the real
// WebSecurityConfig), mirroring AdminControllerV4SecurityTest.
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test")
@Tag("integration")
class MigrationStatusControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var migrationLifecycleGate: MigrationLifecycleGate

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    @DisplayName("SYS-055: anonymous GET /migration-status returns 200 with running=false when no job is active")
    fun `SYS-055 anonymous probe returns running false when gate is free`() {
        `when`(migrationLifecycleGate.current()).thenReturn(null)

        mvc
            .perform(get("/rest/api/4/migration-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.kind").doesNotExist())
    }

    @Test
    @DisplayName("SYS-055: anonymous GET /migration-status returns 200 with running=true + kind while a job is active")
    fun `SYS-055 anonymous probe returns running true with kind while a migration runs`() {
        `when`(migrationLifecycleGate.current()).thenReturn(
            MigrationLifecycleGate.ActiveJob(
                kind = MigrationLifecycleGate.JobKind.COMPONENTS,
                jobId = "00000000-0000-0000-0000-000000000000",
            ),
        )

        mvc
            .perform(get("/rest/api/4/migration-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.kind").value("COMPONENTS"))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureTestDataDir() {
            val resourcesPath: Path =
                Paths.get(MigrationStatusControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
            System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", resourcesPath.toString())
        }
    }
}
