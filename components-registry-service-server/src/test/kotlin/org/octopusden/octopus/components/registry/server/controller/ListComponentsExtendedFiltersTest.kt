package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * CRS-PR2 — extended-search list filters: clientCode, solution, jiraProjectKey,
 * jiraTechnical, vcsPath, productionBranch, parentComponentName, groupKey.
 *
 * Focused integration test on the H2 `ft-db` profile (does NOT extend the global
 * Groovy fixtures). Also pins the load-bearing pagination concern for the
 * VCS two-level JOIN: a component with multiple BASE VCS entries must be counted
 * ONCE under a `?vcsPath=` filter (distinct), not once per entry.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsExtendedFiltersTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    // R1: a ComponentGroup is migration-owned, never assigned via the API, so the
    // `?groupKey=` filter test seeds the group + link directly through the repos.
    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var componentGroupRepository: ComponentGroupRepository

    init {
        val testResourcesPath =
            Paths.get(ListComponentsExtendedFiltersTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    /** POST a component from a raw JSON body; returns its id. Expects 201. */
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

    private fun names(vararg params: Pair<String, String>): Set<String> {
        var request = get("/rest/api/4/components").with(viewerJwt()).param("size", "300")
        for ((k, v) in params) request = request.param(k, v)
        val body = mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(body)["content"].map { it["name"].asText() }.toSet()
    }

    private fun page(vararg params: Pair<String, String>): com.fasterxml.jackson.databind.JsonNode {
        // size=1 so the totalElements assertion genuinely exercises the COUNT
        // query (where a missing distinct would inflate the count).
        var request = get("/rest/api/4/components").with(viewerJwt()).param("size", "1")
        for ((k, v) in params) request = request.param(k, v)
        val body = mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun baseBody(
        name: String,
        extraTop: String = "",
        build: String = """"build":{"buildSystem":"MAVEN"}""",
    ): String =
        """{"name":"$name","displayName":"$name"$extraTop,""" +
            """"group":{"groupKey":"org.example.test","isFake":false},""" +
            """"baseConfiguration":{$build}}"""

    @Test
    @DisplayName("SYS-046: ?clientCode= is an exact multi-value (IN) match, not a substring")
    fun `SYS-046 clientCode exact multi-value filter`() {
        val match = uniqueName("ext_cc_match")
        val other = uniqueName("ext_cc_other")
        val third = uniqueName("ext_cc_third")
        create(baseBody(match, ""","clientCode":"ACME-PORTAL""""))
        create(baseBody(other, ""","clientCode":"OTHER-CC""""))
        create(baseBody(third, ""","clientCode":"THIRD-CC""""))
        // Exact: a partial value no longer matches (was a substring LIKE pre-SYS-046).
        val partial = names("clientCode" to "ACME")
        assert(!partial.contains(match)) { "partial 'ACME' must not match 'ACME-PORTAL' after LIKE→IN; got $partial" }
        // Exact single value matches only the exact code.
        val exact = names("clientCode" to "ACME-PORTAL")
        assert(exact.contains(match)) { "expected $match in $exact" }
        assert(!exact.contains(other)) { "did not expect $other in $exact" }
        // Multi-value OR: ACME-PORTAL,OTHER-CC returns both, excludes the third.
        val multi = names("clientCode" to "ACME-PORTAL,OTHER-CC")
        assert(multi.contains(match) && multi.contains(other)) { "expected both in $multi" }
        assert(!multi.contains(third)) { "did not expect $third in $multi" }
    }

    @Test
    @DisplayName("?solution=true returns only solution components")
    fun solutionFilter() {
        val sol = uniqueName("ext_sol_true")
        val notSol = uniqueName("ext_sol_false")
        create(baseBody(sol, ""","solution":true"""))
        create(baseBody(notSol, ""","solution":false"""))
        val n = names("solution" to "true")
        assert(n.contains(sol)) { "expected $sol in $n" }
        assert(!n.contains(notSol)) { "did not expect $notSol in $n" }
    }

    @Test
    @DisplayName("SYS-045: ?distributionExplicit=true returns only explicit components")
    fun `SYS-045 distributionExplicit filter returns only explicit components`() {
        val yes = uniqueName("ext_de_true")
        val no = uniqueName("ext_de_false")
        create(baseBody(yes, ""","distributionExplicit":true"""))
        create(baseBody(no, ""","distributionExplicit":false"""))
        val n = names("distributionExplicit" to "true")
        assert(n.contains(yes)) { "expected $yes in $n" }
        assert(!n.contains(no)) { "did not expect $no in $n" }
    }

    @Test
    @DisplayName("SYS-045: ?distributionExternal=true returns only external components")
    fun `SYS-045 distributionExternal filter returns only external components`() {
        val yes = uniqueName("ext_dx_true")
        val no = uniqueName("ext_dx_false")
        create(baseBody(yes, ""","distributionExternal":true"""))
        create(baseBody(no, ""","distributionExternal":false"""))
        val n = names("distributionExternal" to "true")
        assert(n.contains(yes)) { "expected $yes in $n" }
        assert(!n.contains(no)) { "did not expect $no in $n" }
    }

    @Test
    @DisplayName("SYS-046: ?jiraProjectKey= is an exact multi-value (IN) match on the BASE row")
    fun `SYS-046 jiraProjectKey exact multi-value filter`() {
        val match = uniqueName("ext_jpk_match")
        val other = uniqueName("ext_jpk_other")
        create(baseBody(match, build = """"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"ZZJIRA"}"""))
        create(baseBody(other, build = """"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"AAOTHER"}"""))
        // Exact (case-sensitive): a lowercase value no longer matches.
        assert(!names("jiraProjectKey" to "zzjira").contains(match)) {
            "lowercase jiraProjectKey must not match after LIKE→IN"
        }
        val exact = names("jiraProjectKey" to "ZZJIRA")
        assert(exact.contains(match)) { "expected $match in $exact" }
        assert(!exact.contains(other)) { "did not expect $other in $exact" }
        // Multi-value OR returns both.
        val multi = names("jiraProjectKey" to "ZZJIRA,AAOTHER")
        assert(multi.contains(match) && multi.contains(other)) { "expected both in $multi" }
    }

    @Test
    @DisplayName("?jiraTechnical=true returns only components whose BASE jira is technical")
    fun jiraTechnicalFilter() {
        val tech = uniqueName("ext_jt_true")
        val notTech = uniqueName("ext_jt_false")
        create(baseBody(tech, build = """"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"JTT","technical":true}"""))
        create(baseBody(notTech, build = """"build":{"buildSystem":"MAVEN"},"jira":{"projectKey":"JTF","technical":false}"""))
        val n = names("jiraTechnical" to "true")
        assert(n.contains(tech)) { "expected $tech in $n" }
        assert(!n.contains(notTech)) { "did not expect $notTech in $n" }
    }

    @Test
    @DisplayName("SYS-046: ?parentComponentName= returns children of any listed parent (multi-value)")
    fun `SYS-046 parentComponentName multi-value filter`() {
        val parentA = uniqueName("ext_p_parentA")
        val parentB = uniqueName("ext_p_parentB")
        create(baseBody(parentA, ""","canBeParent":true"""))
        create(baseBody(parentB, ""","canBeParent":true"""))
        val childA = uniqueName("ext_p_childA")
        create(baseBody(childA, ""","parentComponentName":"$parentA""""))
        val childB = uniqueName("ext_p_childB")
        create(baseBody(childB, ""","parentComponentName":"$parentB""""))
        val standalone = uniqueName("ext_p_standalone")
        create(baseBody(standalone))
        // Single value (backward compat).
        val single = names("parentComponentName" to parentA)
        assert(single.contains(childA)) { "expected $childA in $single" }
        assert(!single.contains(childB)) { "did not expect $childB in $single" }
        // Multi-value OR: children of A OR B; the standalone is excluded.
        val multi = names("parentComponentName" to "$parentA,$parentB")
        assert(multi.contains(childA) && multi.contains(childB)) { "expected both children in $multi" }
        assert(!multi.contains(standalone)) { "did not expect $standalone in $multi" }
    }

    @Test
    @DisplayName("SYS-046: ?groupKey= is an exact multi-value (IN) match on the owning group key")
    fun `SYS-046 groupKey exact multi-value filter`() {
        val unique = uniqueName("grp")
        val groupKey = "org.example.$unique"
        val inGroup = uniqueName("ext_g_in")
        // Create via the API (valid component, null group), then attach the group
        // directly: groups are migration-owned aggregator membership and the API
        // never assigns one (R1). This still exercises the `?groupKey=` JOIN.
        create(baseBody(inGroup))
        val other = uniqueName("ext_g_other")
        create(baseBody(other))
        val group =
            componentGroupRepository.save(ComponentGroupEntity(groupKey = groupKey, isFake = false))
        val entity = componentRepository.findByComponentKey(inGroup)!!
        entity.componentGroup = group
        componentRepository.save(entity)

        // Exact: the bare suffix no longer matches (was a substring LIKE pre-SYS-046).
        assert(!names("groupKey" to unique).contains(inGroup)) {
            "partial groupKey '$unique' must not match '$groupKey' after LIKE→IN"
        }
        val n = names("groupKey" to groupKey)
        assert(n.contains(inGroup)) { "expected $inGroup in $n" }
        assert(!n.contains(other)) { "did not expect $other in $n" }
    }

    @Test
    @DisplayName(
        "compat: a FAKE aggregator self-linked to its own fake group is hidden from the list; " +
            "its members, ordinary components, and a REAL self-linked aggregator are not",
    )
    fun fakeAggregatorSelfLinkedGroupExcludedFromList() {
        // Reproduces the compat-fix marker in isolation: the importer gives a FAKE aggregator a
        // ComponentEntity row whose componentGroup is its OWN fake group, i.e.
        // group.isFake == true AND group.groupKey == componentKey. buildSpecification must hide
        // exactly that row from the v4 list — and nothing else. Seeded via the repos (groups are
        // migration-owned; the API never assigns one — R1), so this stays a focused test that
        // does not depend on the global Groovy fixtures.
        val aggKey = uniqueName("fakeagg")
        val memberKey = uniqueName("fakeagg_member")
        val ordinary = uniqueName("fakeagg_ordinary")
        val realAggKey = uniqueName("realagg")
        create(baseBody(aggKey)) // the FAKE aggregator stub: componentKey == its fake group's key
        create(baseBody(memberKey)) // a real member of that fake group
        create(baseBody(ordinary)) // unrelated, group-less control
        create(baseBody(realAggKey)) // a REAL aggregator self-linked to its own (non-fake) group

        // Count BEFORE any group linking: all four are ordinary, listed components.
        val totalBefore = page()["totalElements"].asLong()

        // is_fake=true group keyed by the stub's OWN componentKey → the self-link exclusion marker.
        val fakeGroup = componentGroupRepository.save(ComponentGroupEntity(groupKey = aggKey, isFake = true))
        val stub = componentRepository.findByComponentKey(aggKey)!!
        stub.componentGroup = fakeGroup
        componentRepository.save(stub)
        val member = componentRepository.findByComponentKey(memberKey)!!
        member.componentGroup = fakeGroup
        componentRepository.save(member)

        // A REAL aggregator is ALSO self-linked (group.groupKey == componentKey) but is_fake=false,
        // so it must NOT be excluded — proving the predicate is gated on is_fake, not the self-link.
        val realGroup = componentGroupRepository.save(ComponentGroupEntity(groupKey = realAggKey, isFake = false))
        val realStub = componentRepository.findByComponentKey(realAggKey)!!
        realStub.componentGroup = realGroup
        componentRepository.save(realStub)

        val n = names()
        assert(!n.contains(aggKey)) { "FAKE aggregator stub $aggKey must be excluded from the v4 list; got $n" }
        assert(n.contains(memberKey)) { "member $memberKey (not the group owner) must remain in the list; got $n" }
        assert(n.contains(ordinary)) { "ordinary group-less $ordinary must remain in the list; got $n" }
        assert(n.contains(realAggKey)) { "REAL self-linked aggregator $realAggKey must remain (is_fake=false); got $n" }

        // The COUNT query must also drop exactly the one fake-group owner: the LEFT join must
        // not inflate totalElements, and the exclusion must apply to the count, not just the page.
        val totalAfter = page()["totalElements"].asLong()
        assert(totalAfter == totalBefore - 1) {
            "v4 list count must fall by exactly 1 (only the fake aggregator owner drops out); " +
                "before=$totalBefore after=$totalAfter"
        }
    }

    @Test
    @DisplayName("?vcsPath= matches a BASE VCS entry; a multi-entry component is counted ONCE (distinct)")
    fun vcsPathFilterAndPaginationDistinct() {
        val multi = uniqueName("ext_vcs_multi")
        // Two VCS entries on the BASE row, both matching the filter token.
        create(
            baseBody(
                multi,
                build = """"build":{"buildSystem":"MAVEN"},"vcsEntries":[""" +
                    """{"name":"main","vcsPath":"octo/ext-multi-a"},""" +
                    """{"name":"alt","vcsPath":"octo/ext-multi-b"}]""",
            ),
        )
        val other = uniqueName("ext_vcs_other")
        create(baseBody(other, build = """"build":{"buildSystem":"MAVEN"},"vcsEntries":[{"name":"main","vcsPath":"octo/unrelated"}]"""))

        val n = names("vcsPath" to "ext-multi")
        assert(n.contains(multi)) { "expected $multi in $n" }
        assert(!n.contains(other)) { "did not expect $other in $n" }

        // Pagination/distinct: the multi-entry component must contribute exactly
        // one row to totalElements (a missing distinct would inflate it to 2).
        val json = page("vcsPath" to "ext-multi")
        assert(json["totalElements"].asLong() == 1L) {
            "expected totalElements=1 for the multi-VCS-entry match; got ${json["totalElements"]}"
        }
        assert(json["content"].count { it["name"].asText() == multi } == 1) {
            "expected exactly one content row for $multi"
        }
    }

    @Test
    @DisplayName("?productionBranch= matches a BASE VCS entry's branch")
    fun productionBranchFilter() {
        val match = uniqueName("ext_br_match")
        create(
            baseBody(
                match,
                build = """"build":{"buildSystem":"MAVEN"},"vcsEntries":[{"name":"main","vcsPath":"octo/br","branch":"release/2025"}]""",
            ),
        )
        val other = uniqueName("ext_br_other")
        create(
            baseBody(
                other,
                build = """"build":{"buildSystem":"MAVEN"},"vcsEntries":[{"name":"main","vcsPath":"octo/br2","branch":"master"}]""",
            ),
        )
        val n = names("productionBranch" to "release")
        assert(n.contains(match)) { "expected $match in $n" }
        assert(!n.contains(other)) { "did not expect $other in $n" }
    }

    @Test
    @DisplayName("blank extended filter value is treated as no filter")
    fun blankIsNoFilter() {
        val seed = uniqueName("ext_blank_seed")
        create(baseBody(seed))
        val without = names()
        val withBlank = names("clientCode" to "")
        assert(without == withBlank) { "blank clientCode should match no-filter; without=$without blank=$withBlank" }
    }
}
