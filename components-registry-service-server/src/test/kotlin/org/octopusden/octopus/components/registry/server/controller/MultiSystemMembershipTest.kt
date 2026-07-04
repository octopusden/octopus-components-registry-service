package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.SystemEntity
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Regression guard for the multi-system membership drop that surfaced in a
 * downstream system-release report: a component that belongs to MORE THAN ONE
 * system (a component can legitimately be classified under several system
 * codes) was silently narrowed to its FIRST system on the DB-mode write /
 * import path, so a report filtered by one of its OTHER systems dropped the
 * component entirely.
 *
 * The affected system codes are not exercised by production traffic, so the
 * request-replay compat baseline CRS gates its merges on never touches this
 * path — a single-value regression here slipped past the baseline entirely.
 * This test closes that coverage gap by pinning multi-system membership at the
 * v4 API round-trip: a component created with two systems must be returned by
 * a filter for EITHER system, and its detail/summary must echo BOTH.
 *
 * Uses raw-JSON create bodies (not the typed DTO) so the assertions describe
 * wire behaviour rather than the Kotlin field shape. System codes are
 * randomised neutral tokens (no production/internal code literals).
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
class MultiSystemMembershipTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var systemRepository: SystemRepository

    init {
        val testResourcesPath =
            Paths.get(MultiSystemMembershipTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueSysCode(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(6)}"

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    /** Pre-seed the master `systems` dictionary — the write-side validator rejects unknown codes. */
    private fun seedSystem(code: String) {
        if (systemRepository.findByCode(code) == null) {
            systemRepository.save(SystemEntity(code = code))
        }
    }

    private fun createComponentWithSystems(
        name: String,
        systems: List<String>,
    ) {
        systems.forEach(::seedSystem)
        val systemsJson = systems.joinToString(",") { "\"$it\"" }
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"$name","displayName":"$name","systems":[$systemsJson],""" +
                            """"componentOwner":"owner1",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
            ).andExpect(status().isCreated)
    }

    private fun fetchNames(vararg params: Pair<String, String>): Set<String> {
        var request = get("/rest/api/4/components").with(viewerJwt()).param("size", "200")
        for ((key, value) in params) request = request.param(key, value)
        val body = mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(body)["content"].map { it["name"].asText() }.toSet()
    }

    @Test
    @DisplayName("a component with two systems is returned by a filter for EITHER system")
    fun `multi-system component matches each of its systems`() {
        val first = uniqueSysCode("ALFA")
        val second = uniqueSysCode("BETA")
        val dual = uniqueName("dual_system")
        val secondOnly = uniqueName("second_only")
        createComponentWithSystems(dual, listOf(first, second))
        createComponentWithSystems(secondOnly, listOf(second))

        val byFirst = fetchNames("system" to first)
        assertTrue(byFirst.contains(dual)) { "expected $dual under ?system=$first; got $byFirst" }

        // The second-system leg — the exact drop the downstream report hit. Both
        // the dual-system component and the second-only one must appear.
        val bySecond = fetchNames("system" to second)
        assertTrue(bySecond.contains(dual)) { "expected $dual under ?system=$second; got $bySecond" }
        assertTrue(bySecond.contains(secondOnly)) { "expected $secondOnly under ?system=$second; got $bySecond" }
    }

    @Test
    @DisplayName("detail + summary echo the full multi-system set")
    fun `detail and summary return all systems`() {
        val first = uniqueSysCode("ALFA")
        val second = uniqueSysCode("BETA")
        val name = uniqueName("dual_detail")
        createComponentWithSystems(name, listOf(first, second))

        val detailBody =
            mvc
                .perform(get("/rest/api/4/components/{name}", name).with(viewerJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val detailSystems = objectMapper.readTree(detailBody)["systems"].map { it.asText() }.toSet()
        assertEquals(setOf(first, second), detailSystems, "detail.systems mismatch: $detailBody")

        val listBody =
            mvc
                .perform(get("/rest/api/4/components").with(viewerJwt()).param("size", "200"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val summary = objectMapper.readTree(listBody)["content"].first { it["name"].asText() == name }
        val summarySystems = summary["systems"].map { it.asText() }.toSet()
        assertEquals(setOf(first, second), summarySystems, "summary.systems mismatch")
    }
}
