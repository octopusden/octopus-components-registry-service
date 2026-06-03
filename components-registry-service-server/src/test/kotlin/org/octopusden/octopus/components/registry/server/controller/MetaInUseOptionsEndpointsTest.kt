package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
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
 * SYS-046 — the four in-use meta option-list endpoints that back the extended-search
 * multi-select dropdowns: `/meta/client-codes`, `/meta/jira-project-keys`,
 * `/meta/parent-component-names`, `/meta/group-keys`. Each returns sorted distinct
 * values **actually in use**, null/blank-filtered, 200 + JSON array (never 404) —
 * parity with `/meta/owners` (MetaOptionsEndpointsTest covers the enum/labels/systems
 * family). Focused ft-db test; does NOT extend the global Groovy fixtures.
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
class MetaInUseOptionsEndpointsTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var componentGroupRepository: ComponentGroupRepository

    init {
        val testResourcesPath =
            Paths.get(MetaInUseOptionsEndpointsTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun create(bodyJson: String): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun baseBody(
        name: String,
        extraTop: String = "",
        build: String = """"build":{"buildSystem":"MAVEN"}""",
    ): String =
        """{"name":"$name","displayName":"$name"$extraTop,""" +
            """"baseConfiguration":{$build}}"""

    private fun metaList(path: String): List<String> {
        val body =
            mvc
                .perform(get(path).with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).map { it.asText() }
    }

    @Test
    @DisplayName("SYS-046: GET /meta/client-codes returns sorted distinct in-use client codes")
    fun `SYS-046 meta client-codes returns sorted distinct in-use values`() {
        val a = uniqueName("ZCC")
        val b = uniqueName("ACC")
        create(baseBody(uniqueName("cc_one"), ""","clientCode":"$a""""))
        create(baseBody(uniqueName("cc_two"), ""","clientCode":"$b""""))
        // Duplicate the first code on a separate component to exercise DISTINCT.
        create(baseBody(uniqueName("cc_three"), ""","clientCode":"$a""""))

        val all = metaList("/rest/api/4/components/meta/client-codes")
        val seeded = all.filter { it == a || it == b }
        assert(seeded == listOf(a, b).sorted()) { "expected seeded client codes sorted ascending; got $seeded" }
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
        assert(all.none { it.isBlank() }) { "expected no blank entries; got $all" }
    }

    @Test
    @DisplayName("SYS-046: GET /meta/jira-project-keys returns sorted distinct BASE-row keys in use")
    fun `SYS-046 meta jira-project-keys returns sorted distinct in-use values`() {
        val a = uniqueName("ZJP")
        val b = uniqueName("AJP")
        create(baseBody(uniqueName("jpk_one"), build = """"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"$a"}"""))
        create(baseBody(uniqueName("jpk_two"), build = """"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"$b"}"""))

        val all = metaList("/rest/api/4/components/meta/jira-project-keys")
        val seeded = all.filter { it == a || it == b }
        assert(seeded == listOf(a, b).sorted()) { "expected seeded jira project keys sorted ascending; got $seeded" }
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
    }

    @Test
    @DisplayName("SYS-046: GET /meta/parent-component-names lists only keys actually referenced as a parent")
    fun `SYS-046 meta parent-component-names lists only referenced parents`() {
        val referenced = uniqueName("mp_referenced")
        val unreferenced = uniqueName("mp_unreferenced")
        // Both are can-be-parent candidates, but only `referenced` is actually referenced.
        create(baseBody(referenced, ""","canBeParent":true"""))
        create(baseBody(unreferenced, ""","canBeParent":true"""))
        create(baseBody(uniqueName("mp_child"), ""","parentComponentName":"$referenced""""))

        val all = metaList("/rest/api/4/components/meta/parent-component-names")
        assert(all.contains(referenced)) { "expected referenced parent $referenced in $all" }
        assert(!all.contains(unreferenced)) {
            "can-be-parent-but-unreferenced $unreferenced must NOT appear (this is the in-use ref set); got $all"
        }
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
    }

    @Test
    @DisplayName("SYS-046: the four in-use meta endpoints always return 200 + a JSON array (never 404)")
    fun `SYS-046 meta endpoints always return 200 array`() {
        // AC#7 shape contract: 200 + array regardless of DB state — the Portal's
        // lazy-activation guard is for the transitional pre-deploy window only.
        listOf(
            "/rest/api/4/components/meta/client-codes",
            "/rest/api/4/components/meta/jira-project-keys",
            "/rest/api/4/components/meta/parent-component-names",
            "/rest/api/4/components/meta/group-keys",
        ).forEach { path ->
            mvc
                .perform(get(path).with(viewerJwt()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }
    }

    @Test
    @DisplayName("SYS-046: GET /meta/group-keys lists only group keys with at least one member")
    fun `SYS-046 meta group-keys lists only groups with members`() {
        val withMember = "org.example.${uniqueName("gk_with")}"
        val memberless = "org.example.${uniqueName("gk_memberless")}"
        // A group with a linked member (groups are migration-owned; seed via repos — R1).
        val member = uniqueName("gk_member")
        create(baseBody(member))
        val group = componentGroupRepository.save(ComponentGroupEntity(groupKey = withMember, isFake = false))
        val entity = componentRepository.findByComponentKey(member)!!
        entity.componentGroup = group
        componentRepository.save(entity)
        // A group with no members at all.
        componentGroupRepository.save(ComponentGroupEntity(groupKey = memberless, isFake = false))

        val all = metaList("/rest/api/4/components/meta/group-keys")
        assert(all.contains(withMember)) { "expected group-with-member $withMember in $all" }
        assert(!all.contains(memberless)) { "memberless group $memberless must NOT appear; got $all" }
        assert(all.size == all.toSet().size) { "expected no duplicates; got $all" }
    }

    @Test
    @DisplayName("SYS-046: /meta/group-keys excludes a fake self-owned aggregator whose only member is its own stub")
    fun `SYS-046 meta group-keys excludes fake self-only aggregator`() {
        // A FAKE aggregator self-linked to its own group (group.isFake=true,
        // group.groupKey == stub.componentKey) is always hidden from the v4 list
        // (buildSpecification). With NO real members, ?groupKey=<key> returns an empty
        // page, so /meta/group-keys must NOT advertise it (no dead option). A fake group
        // WITH a real (non-stub) child IS a valid filter target and must still appear.
        val selfOnlyKey = uniqueName("gk_fakeself")
        create(baseBody(selfOnlyKey)) // the stub; componentKey == its fake group's key
        val selfOnlyGroup = componentGroupRepository.save(ComponentGroupEntity(groupKey = selfOnlyKey, isFake = true))
        val stub = componentRepository.findByComponentKey(selfOnlyKey)!!
        stub.componentGroup = selfOnlyGroup
        componentRepository.save(stub)

        val withChildKey = uniqueName("gk_fakechild")
        create(baseBody(withChildKey)) // the stub
        val realChild = uniqueName("gk_fakechild_member")
        create(baseBody(realChild))
        val withChildGroup = componentGroupRepository.save(ComponentGroupEntity(groupKey = withChildKey, isFake = true))
        val withChildStub = componentRepository.findByComponentKey(withChildKey)!!
        withChildStub.componentGroup = withChildGroup
        componentRepository.save(withChildStub)
        val child = componentRepository.findByComponentKey(realChild)!!
        child.componentGroup = withChildGroup
        componentRepository.save(child)

        val all = metaList("/rest/api/4/components/meta/group-keys")
        assert(!all.contains(selfOnlyKey)) {
            "fake self-only aggregator $selfOnlyKey must NOT appear (its only member is the hidden stub); got $all"
        }
        assert(all.contains(withChildKey)) {
            "fake aggregator $withChildKey with a real child must still appear; got $all"
        }
    }
}
