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

@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsBuildSystemFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsBuildSystemFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponentWithBuildSystem(
        name: String,
        buildSystem: String,
    ) {
        val id = createComponent(name)
        val version = getComponentVersion(id)
        mvc
            .perform(
                patch("/rest/api/4/components/$id")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"version":$version,"baseConfiguration":{"build":{"buildSystem":"$buildSystem"}}}""",
                    ),
            ).andExpect(status().isOk)
    }

    // Post-strict-contract: a component with literally no buildSystem on its BASE
    // configuration row is no longer expressible — the controller rejects with
    // 400 and the DB CHECK rejects direct inserts. The "no build system" case
    // these tests once exercised now reduces to "any non-matching buildSystem
    // value", so the helper creates with `MAVEN` and the assertions
    // `!names.contains(noBuildComp)` continue to hold against a `?buildSystem=GRADLE`
    // filter. The displayed test names still mention "no build config" for
    // historical continuity — observable behaviour (filter excludes
    // non-matching rows) is unchanged.
    private fun createComponentWithoutBuildSystem(name: String) {
        createComponent(name, "MAVEN")
    }

    private fun createComponent(name: String, buildSystem: String = "MAVEN"): String {
        val body =
            mvc
                .perform(
                    post("/rest/api/4/components")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","displayName":"$name",""" +
                                """"group":{"groupKey":"org.example.test","isFake":false},""" +
                                """"baseConfiguration":{"build":{"buildSystem":"$buildSystem"}}}""",
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["id"].asText()
    }

    private fun getComponentVersion(id: String): Long {
        val body =
            mvc
                .perform(get("/rest/api/4/components/$id").with(adminJwt()))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)["version"].asLong()
    }

    @Test
    @DisplayName("listComponents with ?buildSystem=GRADLE returns only components with that buildSystem")
    fun listComponents_byBuildSystem_returnsMatching() {
        val gradleComp = uniqueName("bs_gradle")
        val mavenComp = uniqueName("bs_maven")
        val noBuildComp = uniqueName("bs_none")

        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")
        createComponentWithoutBuildSystem(noBuildComp)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("buildSystem", "GRADLE")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        val names = json["content"].map { it["name"].asText() }.toSet()
        assert(names.contains(gradleComp)) { "expected $gradleComp in $names" }
        assert(!names.contains(mavenComp)) { "did not expect $mavenComp in $names" }
        assert(!names.contains(noBuildComp)) { "did not expect $noBuildComp in $names (no build config)" }
    }

    @Test
    @DisplayName("component without buildConfigurations is excluded when buildSystem filter is set")
    fun listComponents_noBuildConfig_excludedWhenFilterSet() {
        val noBuildComp = uniqueName("bs_edge_none")
        createComponentWithoutBuildSystem(noBuildComp)

        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("buildSystem", "GRADLE"),
            ).andExpect(status().isOk)
            .andExpect(
                jsonPath("$.content[?(@.name == '$noBuildComp')]").doesNotExist(),
            )
    }

    @Test
    @DisplayName("listComponents without buildSystem filter returns 200 (filter is optional)")
    fun listComponents_withoutBuildSystem_ok() {
        mvc.perform(get("/rest/api/4/components").with(viewerJwt())).andExpect(status().isOk)
    }

    @Test
    @DisplayName("listComponents with ?buildSystem=<unknown> returns empty page")
    fun listComponents_byUnknownBuildSystem_returnsEmpty() {
        val unknownBs = uniqueName("UNKNOWN_BS")

        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("buildSystem", unknownBs),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    // -------------------------------------------------------------------
    // SYS-041 — multi-value buildSystem filter with OR semantics
    //
    // Wire format primary is CSV (?buildSystem=GRADLE,MAVEN); Spring also
    // accepts repeatable params (?buildSystem=GRADLE&buildSystem=MAVEN).
    // The controller normalises both via split-by-comma → trim → drop-empty
    // → distinct → null-if-empty. Multi-select semantics is OR because a
    // component has exactly one BASE buildSystem at a time, so AND would
    // never match more than the single-value case.
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
    @DisplayName("SYS-041: ?buildSystem=GRADLE,MAVEN (CSV) returns components matching either (OR semantics)")
    fun `SYS-041 buildSystem GRADLE,MAVEN CSV OR semantics`() {
        val gradleComp = uniqueName("bsmulti_gradle")
        val mavenComp = uniqueName("bsmulti_maven")
        val antComp = uniqueName("bsmulti_other")
        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")
        createComponentWithBuildSystem(antComp, "WHISKEY")

        val names = fetchNames("buildSystem" to "GRADLE,MAVEN")
        assert(names.contains(gradleComp)) { "expected $gradleComp in $names" }
        assert(names.contains(mavenComp)) { "expected $mavenComp in $names" }
        assert(!names.contains(antComp)) { "did not expect $antComp in $names (WHISKEY not selected)" }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=GRADLE&buildSystem=MAVEN (repeatable) matches CSV form")
    fun `SYS-041 buildSystem repeatable params equivalent to CSV`() {
        val gradleComp = uniqueName("bsrep_gradle")
        val mavenComp = uniqueName("bsrep_maven")
        val antComp = uniqueName("bsrep_other")
        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")
        createComponentWithBuildSystem(antComp, "WHISKEY")

        // Spring's @RequestParam List<String> binder accepts repeatable
        // params; the controller's flatMap-split-by-comma collapses them
        // into the same shape as CSV. Two .param() calls with the same
        // key produce two query-string entries (?buildSystem=A&buildSystem=B).
        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("buildSystem", "GRADLE")
                        .param("buildSystem", "MAVEN")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val names = objectMapper.readTree(body)["content"].map { it["name"].asText() }.toSet()
        assert(names.contains(gradleComp)) { "expected $gradleComp in $names" }
        assert(names.contains(mavenComp)) { "expected $mavenComp in $names" }
        assert(!names.contains(antComp)) { "did not expect $antComp in $names (WHISKEY not selected)" }
    }

    @Test
    @DisplayName("SYS-041: single-value ?buildSystem=GRADLE still works (degenerate IN)")
    fun `SYS-041 buildSystem single value backward compatible`() {
        // Backward-compat: the same wire form as the pre-multi-value
        // contract (?buildSystem=GRADLE) must still return GRADLE-only
        // components. A degenerate IN(GRADLE) at the SQL layer is
        // semantically identical to the previous equal(GRADLE).
        val gradleComp = uniqueName("bsbc_gradle")
        val mavenComp = uniqueName("bsbc_maven")
        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")

        val names = fetchNames("buildSystem" to "GRADLE")
        assert(names.contains(gradleComp)) { "expected $gradleComp in $names" }
        assert(!names.contains(mavenComp)) { "did not expect $mavenComp in $names" }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem= (blank) is equivalent to no buildSystem filter")
    fun `SYS-041 buildSystem blank value is no filter`() {
        // Seed a component so the unfiltered page is non-empty even on a
        // fresh DB; equality of the two result sets confirms the blank
        // input took the "no filter" branch instead of producing an
        // empty-string predicate that would silently empty the page.
        val seed = uniqueName("bsblank_seed")
        createComponentWithBuildSystem(seed, "GRADLE")

        val withoutParam = fetchNames()
        val withBlank = fetchNames("buildSystem" to "")
        assert(withoutParam == withBlank) {
            "expected ?buildSystem= to match no filter; without=$withoutParam blank=$withBlank"
        }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=,, (only blanks) is equivalent to no buildSystem filter")
    fun `SYS-041 buildSystem only blanks is no filter`() {
        val seed = uniqueName("bscommas_seed")
        createComponentWithBuildSystem(seed, "GRADLE")

        val withoutParam = fetchNames()
        val withCommas = fetchNames("buildSystem" to ",,")
        assert(withoutParam == withCommas) {
            "expected ?buildSystem=,, to match no filter; without=$withoutParam commas=$withCommas"
        }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=,GRADLE,,MAVEN, behaves as ?buildSystem=GRADLE,MAVEN")
    fun `SYS-041 buildSystem interleaved blanks normalised`() {
        val gradleComp = uniqueName("bsilv_gradle")
        val mavenComp = uniqueName("bsilv_maven")
        val antComp = uniqueName("bsilv_other")
        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")
        createComponentWithBuildSystem(antComp, "WHISKEY")

        val canonical = fetchNames("buildSystem" to "GRADLE,MAVEN")
        val interleaved = fetchNames("buildSystem" to ",GRADLE,,MAVEN,")
        assert(canonical == interleaved) {
            "expected interleaved-blanks input to canonicalise; canonical=$canonical interleaved=$interleaved"
        }
        assert(interleaved.contains(gradleComp)) { "expected $gradleComp in $interleaved" }
        assert(interleaved.contains(mavenComp)) { "expected $mavenComp in $interleaved" }
        assert(!interleaved.contains(antComp)) { "did not expect $antComp in $interleaved" }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=GRADLE%20 (trailing whitespace) matches GRADLE after trim")
    fun `SYS-041 buildSystem trailing whitespace trimmed`() {
        val gradleComp = uniqueName("bstrim_gradle")
        createComponentWithBuildSystem(gradleComp, "GRADLE")

        val names = fetchNames("buildSystem" to "GRADLE ")
        assert(names.contains(gradleComp)) {
            "expected $gradleComp in $names (trailing space should trim)"
        }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=%20GRADLE (leading whitespace) matches GRADLE after trim")
    fun `SYS-041 buildSystem leading whitespace trimmed`() {
        val gradleComp = uniqueName("bsltrim_gradle")
        createComponentWithBuildSystem(gradleComp, "GRADLE")

        val names = fetchNames("buildSystem" to " GRADLE")
        assert(names.contains(gradleComp)) {
            "expected $gradleComp in $names (leading space should trim)"
        }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=GRADLE,GRADLE is equivalent to ?buildSystem=GRADLE (dedupe)")
    fun `SYS-041 buildSystem duplicate value collapsed`() {
        // Behavioural check on the controller's `distinct()` step: a
        // duplicated value must produce the same result set as a single
        // occurrence. IN(GRADLE, GRADLE) and IN(GRADLE) are
        // set-theoretically identical, but dedupe keeps the generated SQL
        // tidy and avoids an avoidable widening in the JPA predicate list.
        val gradleComp = uniqueName("bsdup_gradle")
        val mavenComp = uniqueName("bsdup_maven")
        createComponentWithBuildSystem(gradleComp, "GRADLE")
        createComponentWithBuildSystem(mavenComp, "MAVEN")

        val singleNames = fetchNames("buildSystem" to "GRADLE")
        val duplicatedNames = fetchNames("buildSystem" to "GRADLE,GRADLE")
        assert(singleNames == duplicatedNames) {
            "expected ?buildSystem=GRADLE,GRADLE to match ?buildSystem=GRADLE; single=$singleNames duplicated=$duplicatedNames"
        }
        assert(duplicatedNames.contains(gradleComp)) { "expected $gradleComp in $duplicatedNames" }
        assert(!duplicatedNames.contains(mavenComp)) { "did not expect $mavenComp in $duplicatedNames" }
    }

    @Test
    @DisplayName("SYS-041: ?buildSystem=GRADLE,MAVEN combined with size + sort still paginates+sorts")
    fun `SYS-041 buildSystem with pagination and sort still applied`() {
        // Predictable lexicographic prefixes (aaa < bbb < ccc) so the
        // sort-order assertion is unambiguous. Seed in reverse insertion
        // order so a regression where sort silently fails (e.g., insertion
        // order leaks through) is visibly wrong in the assertion.
        val suffix = UUID.randomUUID().toString().take(6)
        val first = "bspg_aaa_$suffix"
        val second = "bspg_bbb_$suffix"
        val third = "bspg_ccc_$suffix"
        createComponentWithBuildSystem(third, "GRADLE")
        createComponentWithBuildSystem(second, "MAVEN")
        createComponentWithBuildSystem(first, "GRADLE")

        // Pagination sanity: page=0, size=1 returns at most one entry, and
        // totalElements is at least the three seeds (other tests in the
        // shared H2 context may have created their own GRADLE/MAVEN
        // components — GRADLE and MAVEN are static enum values, unlike the
        // uniquely-suffixed labels used by SYS-040 — so we assert a lower
        // bound rather than an exact count).
        val pageBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("buildSystem", "GRADLE,MAVEN")
                        .param("size", "1")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val pageJson = objectMapper.readTree(pageBody)
        assert(pageJson["content"].size() == 1) {
            "expected page size 1; got ${pageJson["content"].size()}: ${pageBody.take(400)}"
        }
        assert(pageJson["totalElements"].asLong() >= 3L) {
            "expected totalElements>=3 (at least the three seeded GRADLE/MAVEN components); got ${pageJson["totalElements"]}"
        }

        // Order regression: fetch a large page so all three seeds land in
        // one response, then filter to the suffix-marked rows and assert
        // componentKey ASC order. A large size ensures the three seeds
        // co-locate even when other tests have populated the shared H2.
        val fullBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("buildSystem", "GRADLE,MAVEN")
                        .param("size", "500")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val returnedNames = objectMapper.readTree(fullBody)["content"].map { it["name"].asText() }
        val seededNames = returnedNames.filter { it.endsWith("_$suffix") }
        assert(seededNames == listOf(first, second, third)) {
            "expected components returned sorted by componentKey ASC ($first, $second, $third); got $seededNames"
        }
    }
}
