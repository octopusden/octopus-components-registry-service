package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * SYS-101 — `GET /rest/api/4/components?involves=<u>[&involvesRoles=…]`: components where the
 * user is the owner OR a release manager OR a security champion (OR across the chosen roles),
 * which the AND-combined owner / releaseManager / securityChampion filters cannot express.
 * Integration layer, `ft-db` profile (H2); each test seeds its own components.
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
class ListComponentsInvolvesFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsInvolvesFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private lateinit var me: String
    private lateinit var owned: String
    private lateinit var asRm: String
    private lateinit var asSc: String
    private lateinit var allThree: String
    private lateinit var unrelated: String

    private fun uniqueName(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createComponent(
        name: String,
        owner: String,
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
        var request = get("/rest/api/4/components").with(viewerJwt()).param("size", "500")
        for ((key, value) in params) request = request.param(key, value)
        val body =
            mvc
                .perform(request)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["content"].map { it["name"].asText() }.toSet()
    }

    /** The seeded components among [names] (the shared H2 holds other tests' data too). */
    private fun seeded(names: Set<String>) = names.intersect(setOf(owned, asRm, asSc, allThree, unrelated))

    @BeforeEach
    fun seed() {
        me = uniqueName("sys101-me")
        val other = uniqueName("sys101-other")
        owned = uniqueName("sys101-owned")
        asRm = uniqueName("sys101-as-rm")
        asSc = uniqueName("sys101-as-sc")
        allThree = uniqueName("sys101-all-three")
        unrelated = uniqueName("sys101-unrelated")
        createComponent(owned, owner = me)
        createComponent(asRm, owner = other, releaseManagers = listOf(other, me))
        createComponent(asSc, owner = other, securityChampions = listOf(me))
        // Owner, RM and SC at once: one row in the result (no join row multiplication).
        createComponent(allThree, owner = me, releaseManagers = listOf(me), securityChampions = listOf(me))
        createComponent(unrelated, owner = other, releaseManagers = listOf(other), securityChampions = listOf(other))
    }

    @Test
    @DisplayName("SYS-101 involves matches owner OR release manager OR security champion")
    fun `SYS-101 involves matches any role`() {
        assertEquals(setOf(owned, asRm, asSc, allThree), seeded(fetchNames("involves" to me)))
    }

    @Test
    @DisplayName("SYS-101 a component where the user holds several roles is returned once")
    fun `SYS-101 no duplicate rows`() {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(viewerJwt()).param("involves", me).param("size", "500"))
                .andReturn()
                .response.contentAsString
        val names = objectMapper.readTree(body)["content"].map { it["name"].asText() }
        assertEquals(1, names.count { it == allThree })
    }

    @Test
    @DisplayName("SYS-101 involvesRoles narrows to the chosen roles, OR across them")
    fun `SYS-101 involvesRoles narrows`() {
        assertEquals(setOf(owned, allThree), seeded(fetchNames("involves" to me, "involvesRoles" to "owner")))
        assertEquals(setOf(asRm, allThree), seeded(fetchNames("involves" to me, "involvesRoles" to "releaseManager")))
        assertEquals(
            setOf(owned, asSc, allThree),
            seeded(fetchNames("involves" to me, "involvesRoles" to "owner,securityChampion")),
        )
    }

    @Test
    @DisplayName("SYS-101 involves combines with other filters via AND")
    fun `SYS-101 combines with archived`() {
        assertEquals(emptySet<String>(), seeded(fetchNames("involves" to me, "archived" to "true")))
    }

    @Test
    @DisplayName("SYS-101 an unknown involvesRoles value is 400")
    fun `SYS-101 unknown role is 400`() {
        mvc
            .perform(get("/rest/api/4/components").with(viewerJwt()).param("involves", me).param("involvesRoles", "admin"))
            .andExpect(status().isBadRequest)
    }
}
