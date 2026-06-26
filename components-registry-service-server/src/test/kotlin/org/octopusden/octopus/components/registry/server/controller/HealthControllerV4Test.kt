package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-057 — `GET /rest/api/4/health/statistics` returns registry-wide counts and
 * people-dimension breakdowns (owner / release-manager / security-champion),
 * computed via SQL GROUP BY. Integration layer, `ft-db` profile (H2 +
 * auto-migrate), each test seeds its own throwaway components.
 *
 * Counts are asserted as DELTAS around the seeding (other tests in the shared H2
 * context contribute their own components), except the per-username maps which use
 * unique usernames so their absolute counts are deterministic.
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
class HealthControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(HealthControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(
        name: String,
        owner: String = "owner_$name",
        releaseManagers: List<String> = emptyList(),
        securityChampions: List<String> = emptyList(),
    ) {
        fun jsonArray(values: List<String>) = values.joinToString(",") { "\"$it\"" }
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"$name","displayName":"$name","componentOwner":"$owner",""" +
                            """"releaseManager":[${jsonArray(releaseManagers)}],""" +
                            """"securityChampion":[${jsonArray(securityChampions)}],""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
            ).andExpect(status().isCreated)
    }

    private fun archiveComponent(name: String) {
        val detailJson =
            mvc
                .perform(get("/rest/api/4/components/$name").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val detail = objectMapper.readTree(detailJson)
        val id = detail["id"].asText()
        val version = detail["version"].asLong()
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"archived":true}"""),
            ).andExpect(status().isOk)
    }

    private fun statistics(): JsonNode {
        val body =
            mvc
                .perform(get("/rest/api/4/health/statistics").with(viewerJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    @Test
    @DisplayName("SYS-057 statistics returns total and active component counts")
    fun `SYS-057 statistics returns total and active component counts`() {
        val before = statistics()
        val totalBefore = before["totalComponents"].asLong()
        val activeBefore = before["activeComponents"].asLong()

        val active1 = uniqueName("sys057_active1")
        val active2 = uniqueName("sys057_active2")
        val archived1 = uniqueName("sys057_archived1")
        createComponent(active1)
        createComponent(active2)
        createComponent(archived1)
        archiveComponent(archived1)

        val after = statistics()
        // 3 new components total, 2 of them still active.
        assert(after["totalComponents"].asLong() == totalBefore + 3) {
            "expected totalComponents to grow by 3 (was $totalBefore, now ${after["totalComponents"]})"
        }
        assert(after["activeComponents"].asLong() == activeBefore + 2) {
            "expected activeComponents to grow by 2 (was $activeBefore, now ${after["activeComponents"]})"
        }
        assert(after["activeComponents"].asLong() <= after["totalComponents"].asLong()) {
            "active must not exceed total"
        }
    }

    @Test
    @DisplayName("SYS-057 statistics groups components by owner RM and SC")
    fun `SYS-057 statistics groups components by owner RM and SC`() {
        val owner = uniqueName("sys057_owner")
        val rm = uniqueName("sys057_rm")
        val sc = uniqueName("sys057_sc")
        val both = uniqueName("sys057_both") // a user who is RM on one and SC on another

        // owner on 2 components
        createComponent(uniqueName("sys057_o_a"), owner = owner)
        createComponent(uniqueName("sys057_o_b"), owner = owner)
        // rm on 3 components
        createComponent(uniqueName("sys057_rm_a"), releaseManagers = listOf(rm))
        createComponent(uniqueName("sys057_rm_b"), releaseManagers = listOf(rm))
        createComponent(uniqueName("sys057_rm_c"), releaseManagers = listOf(rm, both))
        // sc on 1, and `both` as SC on 1
        createComponent(uniqueName("sys057_sc_a"), securityChampions = listOf(sc, both))

        val stats = statistics()
        val byOwner = stats["componentsByOwner"]
        val byRm = stats["componentsByReleaseManager"]
        val bySc = stats["componentsBySecurityChampion"]

        assert(byOwner[owner].asLong() == 2L) { "expected owner $owner count 2, got ${byOwner[owner]}" }
        assert(byRm[rm].asLong() == 3L) { "expected RM $rm count 3, got ${byRm[rm]}" }
        // `both` is RM on 1 and SC on 1 — counted once in each map.
        assert(byRm[both].asLong() == 1L) { "expected RM $both count 1, got ${byRm[both]}" }
        assert(bySc[both].asLong() == 1L) { "expected SC $both count 1, got ${bySc[both]}" }
        assert(bySc[sc].asLong() == 1L) { "expected SC $sc count 1, got ${bySc[sc]}" }
    }

    @Test
    @DisplayName("SYS-057 statistics people breakdowns count active components only")
    fun `SYS-057 statistics people breakdowns count active components only`() {
        // `mixed` is owner/RM/SC on one ACTIVE and one ARCHIVED component → must count 1 in each map.
        val mixed = uniqueName("sys057act_mixed")
        val activeComp = uniqueName("sys057act_active")
        val archivedComp = uniqueName("sys057act_archived")
        createComponent(activeComp, owner = mixed, releaseManagers = listOf(mixed), securityChampions = listOf(mixed))
        createComponent(archivedComp, owner = mixed, releaseManagers = listOf(mixed), securityChampions = listOf(mixed))
        archiveComponent(archivedComp)

        // `archivedOnly` has a single component, archived → must NOT appear in any people map.
        val archivedOnly = uniqueName("sys057act_only")
        val onlyComp = uniqueName("sys057act_onlycomp")
        createComponent(onlyComp, owner = archivedOnly, releaseManagers = listOf(archivedOnly), securityChampions = listOf(archivedOnly))
        archiveComponent(onlyComp)

        val stats = statistics()
        val byOwner = stats["componentsByOwner"]
        val byRm = stats["componentsByReleaseManager"]
        val bySc = stats["componentsBySecurityChampion"]

        // mixed: the archived component is excluded → count 1, not 2.
        assert(byOwner[mixed].asLong() == 1L) { "expected owner $mixed count 1 (archived excluded), got ${byOwner[mixed]}" }
        assert(byRm[mixed].asLong() == 1L) { "expected RM $mixed count 1 (archived excluded), got ${byRm[mixed]}" }
        assert(bySc[mixed].asLong() == 1L) { "expected SC $mixed count 1 (archived excluded), got ${bySc[mixed]}" }

        // archivedOnly: no active components → absent from every map.
        assert(byOwner[archivedOnly] == null) { "expected owner $archivedOnly absent (only archived), got ${byOwner[archivedOnly]}" }
        assert(byRm[archivedOnly] == null) { "expected RM $archivedOnly absent (only archived), got ${byRm[archivedOnly]}" }
        assert(bySc[archivedOnly] == null) { "expected SC $archivedOnly absent (only archived), got ${bySc[archivedOnly]}" }
    }

    @Test
    @DisplayName("SYS-057 statistics is ACCESS_COMPONENTS gated")
    fun `SYS-057 statistics is ACCESS_COMPONENTS gated`() {
        // viewer (ACCESS_COMPONENTS) → 200
        mvc.perform(get("/rest/api/4/health/statistics").with(viewerJwt())).andExpect(status().isOk)
        // unauthenticated → 401
        mvc.perform(get("/rest/api/4/health/statistics")).andExpect(status().isUnauthorized)
    }
}
