package org.octopusden.octopus.components.registry.server.controller

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-056 — `GET /rest/api/4/components?releaseManager=<u>` / `?securityChampion=<u>`
 * filter the component list by the ordered people child collections
 * (component_release_managers / component_security_champions), and the list summary
 * emits `releaseManagers` / `securityChampions` username arrays. Mirrors
 * [ListComponentsOwnerFilterTest] (owner SYS-035/043): integration layer, `ft-db`
 * profile (H2 + auto-migrate), each test seeds its own throwaway components.
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
class ListComponentsPeopleFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsPeopleFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
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

    private fun fetchNames(vararg params: Pair<String, String>): Set<String> {
        var request =
            get("/rest/api/4/components")
                .with(viewerJwt())
                .param("size", "200")
        for ((key, value) in params) {
            request = request.param(key, value)
        }
        val body =
            mvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["content"].map { it["name"].asText() }.toSet()
    }

    @Test
    @DisplayName("SYS-056 releaseManager filter returns only components where user is an RM")
    fun `SYS-056 releaseManager filter returns only components where user is an RM`() {
        val rm = uniqueName("sys056_rm")
        val hasRm = uniqueName("sys056_has_rm")
        val onlySc = uniqueName("sys056_only_sc")
        val onlyOwner = uniqueName("sys056_only_owner")

        createComponent(hasRm, releaseManagers = listOf(rm))
        // Same username as SC, not RM — must NOT match the releaseManager filter.
        createComponent(onlySc, securityChampions = listOf(rm))
        // Same username as owner, not RM — must NOT match either.
        createComponent(onlyOwner, owner = rm)

        val names = fetchNames("releaseManager" to rm)
        assert(names.contains(hasRm)) { "expected $hasRm in $names" }
        assert(!names.contains(onlySc)) { "did not expect $onlySc (user is only an SC there) in $names" }
        assert(!names.contains(onlyOwner)) { "did not expect $onlyOwner (user is only the owner there) in $names" }
    }

    @Test
    @DisplayName("SYS-056 securityChampion filter returns only components where user is an SC")
    fun `SYS-056 securityChampion filter returns only components where user is an SC`() {
        val sc = uniqueName("sys056_sc")
        val hasSc = uniqueName("sys056_has_sc")
        val onlyRm = uniqueName("sys056_only_rm")

        createComponent(hasSc, securityChampions = listOf(sc))
        createComponent(onlyRm, releaseManagers = listOf(sc))

        val names = fetchNames("securityChampion" to sc)
        assert(names.contains(hasSc)) { "expected $hasSc in $names" }
        assert(!names.contains(onlyRm)) { "did not expect $onlyRm (user is only an RM there) in $names" }
    }

    @Test
    @DisplayName("SYS-056 releaseManager CSV is OR across values")
    fun `SYS-056 releaseManager CSV is OR across values`() {
        val alice = uniqueName("sys056or_alice")
        val bob = uniqueName("sys056or_bob")
        val carol = uniqueName("sys056or_carol")
        val compA = uniqueName("sys056or_a")
        val compB = uniqueName("sys056or_b")
        val compC = uniqueName("sys056or_c")
        createComponent(compA, releaseManagers = listOf(alice))
        createComponent(compB, releaseManagers = listOf(bob))
        createComponent(compC, releaseManagers = listOf(carol))

        val names = fetchNames("releaseManager" to "$alice,$bob")
        assert(names.contains(compA)) { "expected $compA in $names" }
        assert(names.contains(compB)) { "expected $compB in $names" }
        assert(!names.contains(compC)) { "did not expect $compC (carol not selected) in $names" }
    }

    @Test
    @DisplayName("SYS-056 unknown releaseManager returns empty")
    fun `SYS-056 unknown releaseManager returns empty`() {
        val unknown = uniqueName("sys056_nobody")
        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("releaseManager", unknown),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    @DisplayName("SYS-056 releaseManager AND securityChampion combine via AND")
    fun `SYS-056 releaseManager and securityChampion combine via AND`() {
        val rm = uniqueName("sys056and_rm")
        val sc = uniqueName("sys056and_sc")
        val both = uniqueName("sys056and_both")
        val onlyRm = uniqueName("sys056and_only_rm")
        createComponent(both, releaseManagers = listOf(rm), securityChampions = listOf(sc))
        createComponent(onlyRm, releaseManagers = listOf(rm))

        val names = fetchNames("releaseManager" to rm, "securityChampion" to sc)
        assert(names.contains(both)) { "expected $both in $names" }
        assert(!names.contains(onlyRm)) { "did not expect $onlyRm (no matching SC) in $names" }
    }

    @Test
    @DisplayName("SYS-056 summary emits releaseManagers and securityChampions arrays")
    fun `SYS-056 summary emits releaseManagers and securityChampions arrays`() {
        val rm1 = uniqueName("sys056sum_rm1")
        val rm2 = uniqueName("sys056sum_rm2")
        val sc1 = uniqueName("sys056sum_sc1")
        val withPeople = uniqueName("sys056sum_with")
        val withoutPeople = uniqueName("sys056sum_without")
        createComponent(withPeople, releaseManagers = listOf(rm1, rm2), securityChampions = listOf(sc1))
        createComponent(withoutPeople)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("search", "sys056sum_")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val content = objectMapper.readTree(body)["content"]

        val withRow = content.first { it["name"].asText() == withPeople }
        val rms = withRow["releaseManagers"].map { it.asText() }
        val scs = withRow["securityChampions"].map { it.asText() }
        // Ordered by sort_order (first = primary).
        assert(rms == listOf(rm1, rm2)) { "expected releaseManagers [$rm1, $rm2] in order, got $rms" }
        assert(scs == listOf(sc1)) { "expected securityChampions [$sc1], got $scs" }

        val withoutRow = content.first { it["name"].asText() == withoutPeople }
        // Emitted-empty-not-null (parity with the `labels` list field).
        assert(withoutRow.has("releaseManagers") && withoutRow["releaseManagers"].isArray) {
            "expected releaseManagers to be an (empty) array, got ${withoutRow["releaseManagers"]}"
        }
        assert(withoutRow["releaseManagers"].size() == 0) { "expected empty releaseManagers, got ${withoutRow["releaseManagers"]}" }
        assert(withoutRow["securityChampions"].size() == 0) { "expected empty securityChampions, got ${withoutRow["securityChampions"]}" }
    }
}
