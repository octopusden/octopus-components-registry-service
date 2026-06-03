package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeMatch
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.mockito.ArgumentMatchers.anyString
import java.nio.file.Paths

/**
 * Stage-2 MockMvc coverage for the employee lookup endpoints consumed by the
 * Portal picker (`GET /rest/api/4/components/meta/employees?search=`) and the
 * inactive badge (`POST /rest/api/4/components/meta/employees/status`).
 *
 * [EmployeeDirectoryService] is a `@MockBean`, so the endpoints' fail-open
 * behavior (empty list / null map values when disabled/unavailable) is asserted
 * via the controller delegation, independent of a live employee-service.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class EmployeeLookupEndpointsV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var employeeDirectory: EmployeeDirectoryService

    @Autowired
    private lateinit var mvc: MockMvc

    init {
        val testResourcesPath =
            Paths.get(EmployeeLookupEndpointsV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("GET /meta/employees returns [{username, active}] for an exact hit")
    fun `search returns match`() {
        `when`(employeeDirectory.search("alice")).thenReturn(listOf(EmployeeMatch("alice", true)))
        mvc.perform(get("/rest/api/4/components/meta/employees").param("search", "alice").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].username").value("alice"))
            .andExpect(jsonPath("$[0].active").value(true))
    }

    @Test
    @DisplayName("GET /meta/employees returns [] when disabled/unavailable (fail-open)")
    fun `search returns empty when disabled`() {
        `when`(employeeDirectory.search(anyString())).thenReturn(emptyList())
        mvc.perform(get("/rest/api/4/components/meta/employees").param("search", "ghost").with(viewerJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    @DisplayName("POST /meta/employees/status maps usernames to active|null")
    fun `status maps usernames`() {
        `when`(employeeDirectory.statuses(anyList()))
            .thenReturn(mapOf("alice" to true, "bob" to false, "ghost" to null))
        mvc.perform(
            post("/rest/api/4/components/meta/employees/status")
                .with(viewerJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""["alice","bob","ghost"]"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.alice").value(true))
            .andExpect(jsonPath("$.bob").value(false))
            .andExpect(jsonPath("$.ghost").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    @DisplayName("both endpoints accept ACCESS_COMPONENTS (admin) and reach the directory")
    fun `endpoints reachable for admin`() {
        `when`(employeeDirectory.search(anyString())).thenReturn(emptyList())
        mvc.perform(get("/rest/api/4/components/meta/employees").param("search", "x").with(adminJwt()))
            .andExpect(status().isOk)
    }
}
