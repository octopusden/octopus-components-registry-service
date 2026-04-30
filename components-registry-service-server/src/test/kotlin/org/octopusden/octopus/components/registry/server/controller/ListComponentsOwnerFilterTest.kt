package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
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
 * SYS-035 — `GET /rest/api/4/components?owner=<username>` filters the component list to
 * components whose `componentOwner` equals the given value. The Portal `ComponentFilters`
 * UI surfaces an owner dropdown populated from `/components/meta/owners`; without this
 * filter param the Portal would have to download all 900+ components to filter
 * client-side.
 *
 * Test layer: integration. The `ft-db` profile gives us H2 + auto-migrate, so each test
 * creates its own throwaway components (deterministic owner assignments) before
 * asserting on the filtered list.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsOwnerFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsOwnerFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(
        name: String,
        owner: String,
    ) {
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"$name","displayName":"$name","componentOwner":"$owner"}"""),
            ).andExpect(status().isCreated)
    }

    @Test
    @DisplayName("SYS-035 listComponents with ?owner=<username> returns only matching components")
    fun listComponents_byOwner_returnsMatching() {
        val mineA = uniqueName("sys035_mine_a")
        val mineB = uniqueName("sys035_mine_b")
        val theirs = uniqueName("sys035_theirs")
        val owner = uniqueName("alice")
        val otherOwner = uniqueName("bob")

        createComponent(mineA, owner)
        createComponent(mineB, owner)
        createComponent(theirs, otherOwner)

        // Use a large page size so the filtered subset fits in a single page even with other
        // components from earlier tests in the same H2 context.
        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("owner", owner)
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val names = json["content"].map { it["name"].asText() }.toSet()
        assert(names.contains(mineA)) { "expected $mineA in $names" }
        assert(names.contains(mineB)) { "expected $mineB in $names" }
        assert(!names.contains(theirs)) { "did not expect $theirs in $names (owner=$otherOwner)" }
    }

    @Test
    @DisplayName("SYS-035 listComponents with ?owner=<unknown> returns empty page")
    fun listComponents_byUnknownOwner_returnsEmpty() {
        val unknownOwner = uniqueName("nobody")

        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("owner", unknownOwner),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    @DisplayName("SYS-035 listComponents without ?owner returns 200 (filter is optional)")
    fun listComponents_withoutOwner_ok() {
        mvc.perform(get("/rest/api/4/components").with(viewerJwt())).andExpect(status().isOk)
    }

    @Test
    @DisplayName("SYS-035 criterion 4: ?owner combines with ?archived via AND")
    fun listComponents_byOwnerAndArchived_combinesViaAnd() {
        val owner = uniqueName("alice_and")
        val activeName = uniqueName("sys035_and_active")
        val archivedName = uniqueName("sys035_and_archived")

        // Two components for the same owner: one active, one archived.
        createComponent(activeName, owner)
        createComponent(archivedName, owner)
        archiveComponent(archivedName)

        // owner=<owner> AND archived=true → only the archived one
        val onlyArchivedJson =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("owner", owner)
                        .param("archived", "true")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val onlyArchivedNames = objectMapper.readTree(onlyArchivedJson)["content"].map { it["name"].asText() }.toSet()
        assert(onlyArchivedNames == setOf(archivedName)) {
            "expected only $archivedName, got $onlyArchivedNames"
        }

        // owner=<owner> AND archived=false → only the active one
        val onlyActiveJson =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("owner", owner)
                        .param("archived", "false")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val onlyActiveNames = objectMapper.readTree(onlyActiveJson)["content"].map { it["name"].asText() }.toSet()
        assert(onlyActiveNames == setOf(activeName)) {
            "expected only $activeName, got $onlyActiveNames"
        }
    }

    private fun archiveComponent(name: String) {
        // Re-fetch to obtain id + version, then PATCH with archived=true. Goes through the
        // real ComponentControllerV4 PATCH path, so it generates an audit row and increments
        // @Version — same shape a Portal "Archive" button would use.
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
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"archived":true}"""),
            ).andExpect(status().isOk)
    }
}
