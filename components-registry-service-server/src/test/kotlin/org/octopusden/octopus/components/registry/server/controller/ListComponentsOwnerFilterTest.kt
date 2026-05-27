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
                    .content(
                        """{"name":"$name","displayName":"$name","componentOwner":"$owner",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
                            """"baseConfiguration":{"build":{"buildSystem":"MAVEN"}}}""",
                    ),
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

    // -------------------------------------------------------------------
    // SYS-043 — multi-value owner filter with OR semantics
    //
    // Wire format primary is CSV (?owner=alice,bob); Spring also accepts
    // repeatable params (?owner=alice&owner=bob). Controller normalises
    // both via split-by-comma → trim → drop-empty → distinct → null-if-empty.
    // Multi-select semantics is OR — "components owned by any of these
    // people". componentOwner is a scalar column on ComponentEntity, so
    // the Specification needs no JOIN and no query.distinct(true); a
    // single root.get(componentOwner).in(...) predicate suffices.
    // -------------------------------------------------------------------

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
    @DisplayName("SYS-043: ?owner=alice (single value, backward compat) returns components owned by alice")
    fun `SYS-043 owner single value backward compatible`() {
        val alice = uniqueName("sys043_alice")
        val bob = uniqueName("sys043_bob")
        val aliceComp = uniqueName("sys043bc_a")
        val bobComp = uniqueName("sys043bc_b")
        createComponent(aliceComp, alice)
        createComponent(bobComp, bob)

        val names = fetchNames("owner" to alice)
        assert(names.contains(aliceComp)) { "expected $aliceComp in $names" }
        assert(!names.contains(bobComp)) { "did not expect $bobComp in $names" }
    }

    @Test
    @DisplayName("SYS-043: ?owner=alice,bob (CSV) returns components owned by EITHER alice OR bob")
    fun `SYS-043 owner CSV OR semantics`() {
        val alice = uniqueName("sys043or_alice")
        val bob = uniqueName("sys043or_bob")
        val carol = uniqueName("sys043or_carol")
        val compA = uniqueName("sys043or_a")
        val compB = uniqueName("sys043or_b")
        val compC = uniqueName("sys043or_c")
        createComponent(compA, alice)
        createComponent(compB, bob)
        createComponent(compC, carol)

        val names = fetchNames("owner" to "$alice,$bob")
        assert(names.contains(compA)) { "expected $compA in $names" }
        assert(names.contains(compB)) { "expected $compB in $names" }
        assert(!names.contains(compC)) { "did not expect $compC in $names (carol not selected)" }
    }

    @Test
    @DisplayName("SYS-043: ?owner=alice&owner=bob (repeatable) equivalent to CSV")
    fun `SYS-043 owner repeatable params equivalent to CSV`() {
        val alice = uniqueName("sys043rep_alice")
        val bob = uniqueName("sys043rep_bob")
        val carol = uniqueName("sys043rep_carol")
        val compA = uniqueName("sys043rep_a")
        val compB = uniqueName("sys043rep_b")
        val compC = uniqueName("sys043rep_c")
        createComponent(compA, alice)
        createComponent(compB, bob)
        createComponent(compC, carol)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("owner", alice)
                        .param("owner", bob)
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val names = objectMapper.readTree(body)["content"].map { it["name"].asText() }.toSet()
        assert(names.contains(compA)) { "expected $compA in $names" }
        assert(names.contains(compB)) { "expected $compB in $names" }
        assert(!names.contains(compC)) { "did not expect $compC in $names" }
    }

    @Test
    @DisplayName("SYS-043: ?owner= (blank) is equivalent to no owner filter")
    fun `SYS-043 owner blank value is no filter`() {
        val seedOwner = uniqueName("sys043blank_seed_owner")
        val seedComp = uniqueName("sys043blank_seed_comp")
        createComponent(seedComp, seedOwner)

        val withoutParam = fetchNames()
        val withBlank = fetchNames("owner" to "")
        assert(withoutParam == withBlank) {
            "expected ?owner= to match no filter; without=$withoutParam blank=$withBlank"
        }
    }

    @Test
    @DisplayName("SYS-043: ?owner=,, (only blanks) is equivalent to no owner filter")
    fun `SYS-043 owner only blanks is no filter`() {
        val seedOwner = uniqueName("sys043commas_seed_owner")
        val seedComp = uniqueName("sys043commas_seed_comp")
        createComponent(seedComp, seedOwner)

        val withoutParam = fetchNames()
        val withCommas = fetchNames("owner" to ",,")
        assert(withoutParam == withCommas) {
            "expected ?owner=,, to match no filter; without=$withoutParam commas=$withCommas"
        }
    }

    @Test
    @DisplayName("SYS-043: ?owner=,alice,,bob, behaves as ?owner=alice,bob")
    fun `SYS-043 owner interleaved blanks normalised`() {
        val alice = uniqueName("sys043ilv_alice")
        val bob = uniqueName("sys043ilv_bob")
        val carol = uniqueName("sys043ilv_carol")
        val compA = uniqueName("sys043ilv_a")
        val compB = uniqueName("sys043ilv_b")
        val compC = uniqueName("sys043ilv_c")
        createComponent(compA, alice)
        createComponent(compB, bob)
        createComponent(compC, carol)

        val canonical = fetchNames("owner" to "$alice,$bob")
        val interleaved = fetchNames("owner" to ",$alice,,$bob,")
        assert(canonical == interleaved) {
            "expected interleaved-blanks input to canonicalise; canonical=$canonical interleaved=$interleaved"
        }
        assert(interleaved.contains(compA)) { "expected $compA in $interleaved" }
        assert(interleaved.contains(compB)) { "expected $compB in $interleaved" }
        assert(!interleaved.contains(compC)) { "did not expect $compC in $interleaved" }
    }

    @Test
    @DisplayName("SYS-043: ?owner=alice%20 (trailing whitespace) matches alice after trim")
    fun `SYS-043 owner trailing whitespace trimmed`() {
        val alice = uniqueName("sys043trim_alice")
        val tagged = uniqueName("sys043trim_tagged")
        createComponent(tagged, alice)

        val names = fetchNames("owner" to "$alice ")
        assert(names.contains(tagged)) { "expected $tagged in $names (trailing space should trim)" }
    }

    @Test
    @DisplayName("SYS-043: ?owner=%20alice (leading whitespace) matches alice after trim")
    fun `SYS-043 owner leading whitespace trimmed`() {
        val alice = uniqueName("sys043ltrim_alice")
        val tagged = uniqueName("sys043ltrim_tagged")
        createComponent(tagged, alice)

        val names = fetchNames("owner" to " $alice")
        assert(names.contains(tagged)) { "expected $tagged in $names (leading space should trim)" }
    }

    @Test
    @DisplayName("SYS-043: ?owner=alice,alice is equivalent to ?owner=alice (dedupe)")
    fun `SYS-043 owner duplicate value collapsed`() {
        val alice = uniqueName("sys043dup_alice")
        val bob = uniqueName("sys043dup_bob")
        val aliceComp = uniqueName("sys043dup_a")
        val bobComp = uniqueName("sys043dup_b")
        createComponent(aliceComp, alice)
        createComponent(bobComp, bob)

        val single = fetchNames("owner" to alice)
        val duplicated = fetchNames("owner" to "$alice,$alice")
        assert(single == duplicated) {
            "expected ?owner=alice,alice to match ?owner=alice; single=$single duplicated=$duplicated"
        }
        assert(duplicated.contains(aliceComp)) { "expected $aliceComp in $duplicated" }
        assert(!duplicated.contains(bobComp)) { "did not expect $bobComp in $duplicated" }
    }

    @Test
    @DisplayName("SYS-043: ?owner=alice,bob combined with size + sort still paginates+sorts")
    fun `SYS-043 owner with pagination and sort still applied`() {
        // Predictable lexicographic prefixes (aaa < bbb < ccc) and unique
        // owner names so totalElements over the OR set is independent of
        // other tests' fixtures.
        val alice = uniqueName("sys043pg_alice")
        val bob = uniqueName("sys043pg_bob")
        val suffix = UUID.randomUUID().toString().take(6)
        val first = "ownpg_aaa_$suffix"
        val second = "ownpg_bbb_$suffix"
        val third = "ownpg_ccc_$suffix"
        // Reverse insertion order so a sort regression is visibly wrong.
        createComponent(third, bob)
        createComponent(second, alice)
        createComponent(first, alice)

        val pageBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("owner", "$alice,$bob")
                        .param("size", "1")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val pageJson = objectMapper.readTree(pageBody)
        assert(pageJson["content"].size() == 1) {
            "expected page size 1; got ${pageJson["content"].size()}: ${pageBody.take(400)}"
        }
        assert(pageJson["totalElements"].asLong() == 3L) {
            "expected totalElements=3 (three seeded alice/bob components); got ${pageJson["totalElements"]}"
        }

        val fullBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("owner", "$alice,$bob")
                        .param("size", "10")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val returnedNames = objectMapper.readTree(fullBody)["content"].map { it["name"].asText() }
        val seededNames = returnedNames.filter { it.endsWith("_$suffix") }
        assert(seededNames == listOf(first, second, third)) {
            "expected components returned sorted by componentKey ASC ($first, $second, $third); got $seededNames"
        }
    }
}
