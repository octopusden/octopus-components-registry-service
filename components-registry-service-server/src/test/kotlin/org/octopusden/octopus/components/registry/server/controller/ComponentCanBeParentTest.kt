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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * CRS-PR1 — `canBeParent` attribute end-to-end:
 *  - round-trips on create / detail / summary;
 *  - drives the `?canBeParent=true` list filter (parent picker);
 *  - enforces the parent invariants (chosen parent must be canBeParent; a
 *    canBeParent component may not have a parent; can't disable canBeParent
 *    while children reference it);
 *  - update derives the component group from the parent when the parent
 *    actually changes, and `clearParent` removes it.
 *
 * Focused integration test on the H2 `ft-db` profile — does NOT extend the
 * global Groovy fixtures (each case seeds its own uniquely-named components).
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ComponentCanBeParentTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ComponentCanBeParentTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    /** POST a component; returns its id. Expects 201. */
    private fun createComponent(
        name: String,
        canBeParent: Boolean = false,
        parentComponentName: String? = null,
    ): String {
        val parentJson = parentComponentName?.let { ""","parentComponentName":"$it"""" } ?: ""
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","displayName":"$name",""" +
                                """"canBeParent":$canBeParent$parentJson,""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun getJson(id: String) =
        objectMapper.readTree(
            mvc.perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )

    private fun version(id: String): Long = getJson(id)["version"].asLong()

    // -----------------------------------------------------------------------
    // Round-trip
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("canBeParent round-trips on create and detail (default false)")
    fun canBeParent_roundTrips() {
        val plain = createComponent(uniqueName("cbp_plain"))
        val parent = createComponent(uniqueName("cbp_parent"), canBeParent = true)

        assert(!getJson(plain)["canBeParent"].asBoolean()) { "expected default canBeParent=false" }
        assert(getJson(parent)["canBeParent"].asBoolean()) { "expected canBeParent=true" }
    }

    // -----------------------------------------------------------------------
    // Filter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("?canBeParent=true returns only components flagged can-be-parent")
    fun listComponents_canBeParentFilter() {
        val parent = uniqueName("cbp_f_parent")
        val plain = uniqueName("cbp_f_plain")
        createComponent(parent, canBeParent = true)
        createComponent(plain)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("canBeParent", "true")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val content = objectMapper.readTree(body)["content"]
        val names = content.map { it["name"].asText() }.toSet()
        assert(names.contains(parent)) { "expected $parent in $names" }
        assert(!names.contains(plain)) { "did not expect $plain in $names" }
        // canBeParent also round-trips on the summary projection.
        val parentRow = content.first { it["name"].asText() == parent }
        assert(parentRow["canBeParent"].asBoolean()) { "expected summary canBeParent=true for $parent" }
    }

    // -----------------------------------------------------------------------
    // Create-side invariants
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("create with a parent that is NOT canBeParent is rejected (400)")
    fun create_childOfNonParent_rejected() {
        val notParent = uniqueName("cbp_notparent")
        createComponent(notParent) // canBeParent defaults to false

        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"${uniqueName("cbp_child")}","parentComponentName":"$notParent",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("create with a canBeParent parent succeeds")
    fun create_childOfParent_ok() {
        val parent = uniqueName("cbp_ok_parent")
        createComponent(parent, canBeParent = true)
        val child = createComponent(uniqueName("cbp_ok_child"), parentComponentName = parent)
        assert(getJson(child)["parentComponentName"].asText() == parent)
    }

    @Test
    @DisplayName("create with canBeParent=true AND a parent is rejected (a parent cannot have a parent)")
    fun create_parentWithParent_rejected() {
        val parent = uniqueName("cbp_pp_parent")
        createComponent(parent, canBeParent = true)

        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"${uniqueName("cbp_pp_child")}","canBeParent":true,""" +
                            """"parentComponentName":"$parent",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
            ).andExpect(status().isBadRequest)
    }

    // -----------------------------------------------------------------------
    // Update-side derivation + invariants
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("update: setting a parent derives the component into the parent's group")
    fun update_setParent_derivesGroup() {
        val parent = createComponent(uniqueName("cbp_d_parent"), canBeParent = true)
        val parentName = getJson(parent)["name"].asText()
        val child = createComponent(uniqueName("cbp_d_child"))

        mvc
            .perform(
                patch("/rest/api/4/components/$child")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(child)},"parentComponentName":"$parentName"}"""),
            ).andExpect(status().isOk)

        val childGroup = getJson(child)["group"]["groupKey"].asText()
        val parentGroup = getJson(parent)["group"]["groupKey"].asText()
        assert(childGroup == parentGroup) {
            "expected child to join the parent's group; child=$childGroup parent=$parentGroup"
        }
    }

    @Test
    @DisplayName("update: disabling canBeParent while children reference it is rejected (400)")
    fun update_disableCanBeParentWithChildren_rejected() {
        val parent = createComponent(uniqueName("cbp_x_parent"), canBeParent = true)
        val parentName = getJson(parent)["name"].asText()
        createComponent(uniqueName("cbp_x_child"), parentComponentName = parentName)

        mvc
            .perform(
                patch("/rest/api/4/components/$parent")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(parent)},"canBeParent":false}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("update: clearParent removes the parent but PRESERVES the existing group")
    fun update_clearParent_removesParent() {
        val parent = createComponent(uniqueName("cbp_c_parent"), canBeParent = true)
        val parentName = getJson(parent)["name"].asText()
        val child = createComponent(uniqueName("cbp_c_child"), parentComponentName = parentName)

        mvc
            .perform(
                patch("/rest/api/4/components/$child")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":${version(child)},"clearParent":true}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.parentComponentName").doesNotExist())
            // A standalone (non-aggregator) child must keep its group — clearing
            // the parent does not strip it (every component must belong to a group).
            .andExpect(jsonPath("$.group.groupKey").value("org.example.test"))
    }

    @Test
    @DisplayName("update: clearParent + parentComponentName together is rejected (400)")
    fun update_clearParentAndParentName_rejected() {
        val parent = createComponent(uniqueName("cbp_me_parent"), canBeParent = true)
        val parentName = getJson(parent)["name"].asText()
        val child = createComponent(uniqueName("cbp_me_child"))

        mvc
            .perform(
                patch("/rest/api/4/components/$child")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":${version(child)},"clearParent":true,"parentComponentName":"$parentName"}""",
                    ),
            ).andExpect(status().isBadRequest)
    }
}
