package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.mockito.ArgumentMatchers.anyString
import java.nio.file.Paths
import java.util.UUID

/**
 * Stage-1 MockMvc end-to-end coverage for the restored person-field validation
 * on `POST /rest/api/4/components` and `PATCH /rest/api/4/components/{id}`.
 *
 * [EmployeeDirectoryService] is replaced with a `@MockBean` so the active-check
 * path is deterministic without a live employee-service: `isEnabled()` is
 * stubbed per test (false ⇒ flag-off semantics; true ⇒ flag-on), and
 * `isActive(...)` returns a scripted [ActiveStatus]. The DB-backed write path
 * runs against the `ft-db` Testcontainers Postgres (same setup as
 * [V4WriteValidationTest]).
 *
 * Asserts (per the Stage-1 checklist):
 *  - flag off ⇒ NO employee call, BUT required/pattern still enforced;
 *  - flag on ⇒ active pass / inactive 400 / unknown 400 / transport pass;
 *  - conditional regime (RM/SC required only under explicit && external);
 *  - gate-flip on PATCH re-validates final-state RM/SC;
 *  - per-element `"alice,bob"` fails `^\w+$`;
 *  - hidden field skipped (covered in PersonFieldValidatorTest — here we keep
 *    the 400 body shape + endpoint coverage);
 *  - 400 body carries `{errorMessage}` naming the field.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
class PersonFieldValidationV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @MockBean
    private lateinit var employeeDirectory: EmployeeDirectoryService

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(PersonFieldValidationV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueSuffix() = UUID.randomUUID().toString().take(6)

    private fun postCreate(body: String) =
        mvc.perform(
            post("/rest/api/4/components")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun patchComponent(id: String, body: String) =
        mvc.perform(
            patch("/rest/api/4/components/$id")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    /** Valid create with an owner and the explicit&&external gate OFF. */
    private fun validBody(name: String, owner: String = "owner_$name"): String =
        """{"name":"$name","componentOwner":"$owner",""" +
            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""

    // ---------- flag OFF: no employee call, but required/pattern still run ----------

    @Test
    @DisplayName("flag off: blank componentOwner ⇒ 400 with field-named errorMessage, no employee call")
    fun `flag off blank owner rejected`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(false)
        val name = "pf_owner_blank_${uniqueSuffix()}"
        val body =
            """{"name":"$name","componentOwner":"   ",""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("componentOwner")))
        verify(employeeDirectory, never()).isActive(anyString())
    }

    @Test
    @DisplayName("flag off: missing RM/SC under explicit && external ⇒ 400 (releaseManager)")
    fun `flag off missing rm under gate rejected`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(false)
        val name = "pf_rm_missing_${uniqueSuffix()}"
        val body =
            """{"name":"$name","componentOwner":"owner1",""" +
                """"distributionExplicit":true,"distributionExternal":true,""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("releaseManager")))
    }

    @Test
    @DisplayName("flag off: per-element \"alice,bob\" fails ^\\w+\$ under the gate")
    fun `flag off csv element rejected`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(false)
        val name = "pf_csv_${uniqueSuffix()}"
        val body =
            """{"name":"$name","componentOwner":"owner1",""" +
                """"distributionExplicit":true,"distributionExternal":true,""" +
                """"releaseManager":["alice,bob"],"securityChampion":["sc1"],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
        postCreate(body)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("releaseManager")))
    }

    @Test
    @DisplayName("flag off: valid component (gate off) ⇒ 2xx, no employee call (fail-open / disabled)")
    fun `flag off valid passes`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(false)
        postCreate(validBody("pf_valid_off_${uniqueSuffix()}"))
            .andExpect(status().isCreated)
        verify(employeeDirectory, never()).isActive(anyString())
    }

    // ---------- flag ON: active-employee semantics ----------

    @Test
    @DisplayName("flag on: active owner ⇒ 2xx")
    fun `flag on active owner passes`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(true)
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.ACTIVE)
        postCreate(validBody("pf_active_${uniqueSuffix()}")).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("flag on: inactive owner ⇒ 400")
    fun `flag on inactive owner rejected`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(true)
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.INACTIVE)
        postCreate(validBody("pf_inactive_${uniqueSuffix()}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("componentOwner")))
    }

    @Test
    @DisplayName("flag on: unknown owner (NotFound) ⇒ 400")
    fun `flag on unknown owner rejected`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(true)
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.UNKNOWN)
        postCreate(validBody("pf_unknown_${uniqueSuffix()}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("componentOwner")))
    }

    @Test
    @DisplayName("flag on: transport error (UNAVAILABLE) ⇒ 2xx (fail-open)")
    fun `flag on transport error allowed`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(true)
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.UNAVAILABLE)
        postCreate(validBody("pf_unavailable_${uniqueSuffix()}")).andExpect(status().isCreated)
    }

    // ---------- gate-flip on PATCH re-validates final-state RM/SC ----------

    @Test
    @DisplayName("PATCH gate-flip to explicit&&external re-validates RM/SC (active) even if PATCH omits them")
    fun `patch gate flip revalidates rm sc`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(true)
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.ACTIVE)
        // Seed a component with the gate OFF, with RM but no SC.
        val name = "pf_gateflip_${uniqueSuffix()}"
        val seedBody =
            """{"name":"$name","componentOwner":"owner1","releaseManager":["rm1"],""" +
                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}"""
        val seed =
            postCreate(seedBody).andExpect(status().isCreated).andReturn().response.contentAsString
        val node = objectMapper.readTree(seed)
        val id = node["id"].asText()
        val version = node["version"].asLong()
        // Flip the gate ON without re-sending RM/SC. SC is now required (missing) ⇒ 400.
        val patchBody = """{"version":$version,"distributionExplicit":true,"distributionExternal":true}"""
        patchComponent(id, patchBody)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("securityChampion")))
    }

    @Test
    @DisplayName("PATCH that touches neither person fields nor gate does NOT re-run active check (grandfathered)")
    fun `patch unrelated does not recheck`() {
        `when`(employeeDirectory.isEnabled()).thenReturn(true)
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.ACTIVE)
        val name = "pf_grandfather_${uniqueSuffix()}"
        val seed =
            postCreate(validBody(name)).andExpect(status().isCreated).andReturn().response.contentAsString
        val node = objectMapper.readTree(seed)
        val id = node["id"].asText()
        val version = node["version"].asLong()
        // Now the owner becomes inactive in the directory — but a label-only PATCH
        // must NOT re-validate the grandfathered owner.
        `when`(employeeDirectory.isActive(anyString())).thenReturn(ActiveStatus.INACTIVE)
        patchComponent(id, """{"version":$version,"displayName":"renamed"}""")
            .andExpect(status().isOk)
    }
}
