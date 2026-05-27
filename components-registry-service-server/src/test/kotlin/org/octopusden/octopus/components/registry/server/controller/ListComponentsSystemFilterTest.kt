package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Paths
import java.util.UUID

/**
 * Schema v2: `system` was a `text[]` column on the legacy schema, which JPA
 * Criteria couldn't filter portably across H2 PG-compat and Postgres, so the
 * v1 implementation rejected `?system=…` with 400. With the v2 normalised
 * `component_systems` junction table the filter is expressible as a plain
 * JOIN (see `ComponentManagementServiceImpl.buildSpecification`), so the
 * endpoint now returns 200 and a filtered (possibly empty) page.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsSystemFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var systemRepository: org.octopusden.octopus.components.registry.server.repository.SystemRepository

    init {
        val testResourcesPath =
            Paths.get(ListComponentsSystemFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @Test
    @DisplayName("listComponents without filter.system returns 200")
    fun listComponents_noSystemFilter_ok() {
        mvc.perform(get("/rest/api/4/components").with(viewerJwt())).andExpect(status().isOk)
    }

    /**
     * Behaviour, not just status: when the auto-migrated fixture contains
     * components with `system = "CLASSIC"` (see common/TestComponents.groovy),
     * filtering by `?system=CLASSIC` must return a non-empty content list AND
     * every returned row must have `system == "CLASSIC"`. A weaker
     * "expect 200" check would still pass if the new scalar-column filter
     * were silently ignored and the endpoint returned an unfiltered page.
     */
    @Test
    @DisplayName("?system=CLASSIC returns only components whose system == CLASSIC")
    fun systemFilter_includesMatchingComponents() {
        val body =
            mvc
                .perform(get("/rest/api/4/components").with(viewerJwt()).param("system", "CLASSIC").param("size", "200"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        val content = objectMapper.readTree(body).path("content")
        assertTrue(content.isArray && content.size() > 0, "Expected at least one CLASSIC component; got: ${body.take(400)}")
        for (component in content) {
            val system = component.path("system").asText(null)
            assertTrue(
                system == "CLASSIC",
                "Component '${component.path("name").asText()}' returned by ?system=CLASSIC must declare CLASSIC; got system=$system",
            )
        }
    }

    @Test
    @DisplayName("?system=<unknown> returns empty content (not an unfiltered page)")
    fun systemFilter_excludesNonMatchingComponents() {
        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("system", "DEFINITELY_NOT_A_REAL_SYSTEM_xyz123")
                        .param("size", "200"),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val content = objectMapper.readTree(body).path("content")
        assertFalse(
            content.isArray && content.size() > 0,
            "?system=<unknown> must return an empty content array; got ${content.size()} entries: ${body.take(400)}",
        )
    }

    // -------------------------------------------------------------------
    // SYS-042 — multi-value system filter with OR semantics
    //
    // Wire format primary is CSV (?system=A,B); Spring also accepts
    // repeatable params (?system=A&system=B). Controller normalises both
    // via split-by-comma → trim → drop-empty → distinct → null-if-empty.
    // Multi-select semantics is OR — the picker means "components
    // belonging to any of these systems". After collapsing the system
    // model to a scalar (`components.system_code`), each component carries
    // exactly zero-or-one system. The Specification reduces to a plain
    // `IN(...)` predicate against the scalar column — no JOIN — and a
    // component cannot match more than one selection at once.
    // -------------------------------------------------------------------

    private fun uniqueSysCode(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(6)}"

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponentWithSystem(
        name: String,
        system: String,
    ) {
        // Pre-seed the master `systems` table — the service-layer
        // validator rejects codes that aren't in the dictionary.
        if (systemRepository.findByCode(system) == null) {
            systemRepository.save(
                org.octopusden.octopus.components.registry.server.entity.SystemEntity(code = system),
            )
        }
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"$name","displayName":"$name","system":"$system",""" +
                            """"group":{"groupKey":"org.example.test","isFake":false},""" +
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
    @DisplayName("SYS-042: ?system=A returns only components carrying system A (single-value back-compat)")
    fun `SYS-042 system single value backward compatible`() {
        val sysA = uniqueSysCode("sysA")
        val sysB = uniqueSysCode("sysB")
        val onlyA = uniqueName("sys_only_a")
        val onlyB = uniqueName("sys_only_b")
        createComponentWithSystem(onlyA, sysA)
        createComponentWithSystem(onlyB, sysB)

        val names = fetchNames("system" to sysA)
        assert(names.contains(onlyA)) { "expected $onlyA in $names" }
        assert(!names.contains(onlyB)) { "did not expect $onlyB in $names" }
    }

    @Test
    @DisplayName("SYS-042: ?system=A,B (CSV) returns components whose single system is A OR B (OR semantics)")
    fun `SYS-042 system A,B CSV OR semantics`() {
        // After the M:N collapse to single-value, a component cannot carry
        // "both A and B". OR semantics still holds across distinct
        // components: filter `?system=A,B` matches any component whose
        // single `system_code` is in {A, B}.
        val sysA = uniqueSysCode("orsysa")
        val sysB = uniqueSysCode("orsysb")
        val sysC = uniqueSysCode("orsysc")
        val onlyA = uniqueName("sysor_only_a")
        val onlyB = uniqueName("sysor_only_b")
        val onlyC = uniqueName("sysor_only_c")
        createComponentWithSystem(onlyA, sysA)
        createComponentWithSystem(onlyB, sysB)
        createComponentWithSystem(onlyC, sysC)

        val names = fetchNames("system" to "$sysA,$sysB")
        assert(names.contains(onlyA)) { "expected $onlyA in $names" }
        assert(names.contains(onlyB)) { "expected $onlyB in $names" }
        assert(!names.contains(onlyC)) { "did not expect $onlyC in $names (C not selected)" }
    }

    @Test
    @DisplayName("SYS-042: ?system=A&system=B (repeatable) equivalent to CSV")
    fun `SYS-042 system repeatable params equivalent to CSV`() {
        val sysA = uniqueSysCode("repsysa")
        val sysB = uniqueSysCode("repsysb")
        val sysC = uniqueSysCode("repsysc")
        val compA = uniqueName("sysrep_a")
        val compB = uniqueName("sysrep_b")
        val compC = uniqueName("sysrep_c")
        createComponentWithSystem(compA, sysA)
        createComponentWithSystem(compB, sysB)
        createComponentWithSystem(compC, sysC)

        val body =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("system", sysA)
                        .param("system", sysB)
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
    @DisplayName("SYS-042: ?system= (blank) is equivalent to no system filter")
    fun `SYS-042 system blank value is no filter`() {
        val seedSys = uniqueSysCode("sysblank")
        val seed = uniqueName("sysblank_seed")
        createComponentWithSystem(seed, seedSys)

        val withoutParam = fetchNames()
        val withBlank = fetchNames("system" to "")
        assert(withoutParam == withBlank) {
            "expected ?system= to match no filter; without=$withoutParam blank=$withBlank"
        }
    }

    @Test
    @DisplayName("SYS-042: ?system=,, (only blanks) is equivalent to no system filter")
    fun `SYS-042 system only blanks is no filter`() {
        val seedSys = uniqueSysCode("syscommas")
        val seed = uniqueName("syscommas_seed")
        createComponentWithSystem(seed, seedSys)

        val withoutParam = fetchNames()
        val withCommas = fetchNames("system" to ",,")
        assert(withoutParam == withCommas) {
            "expected ?system=,, to match no filter; without=$withoutParam commas=$withCommas"
        }
    }

    @Test
    @DisplayName("SYS-042: ?system=,A,,B, behaves as ?system=A,B (interleaved blanks dropped)")
    fun `SYS-042 system interleaved blanks normalised`() {
        val sysA = uniqueSysCode("ilvsysa")
        val sysB = uniqueSysCode("ilvsysb")
        val sysC = uniqueSysCode("ilvsysc")
        val compA = uniqueName("sysilv_a")
        val compB = uniqueName("sysilv_b")
        val compC = uniqueName("sysilv_c")
        createComponentWithSystem(compA, sysA)
        createComponentWithSystem(compB, sysB)
        createComponentWithSystem(compC, sysC)

        val canonical = fetchNames("system" to "$sysA,$sysB")
        val interleaved = fetchNames("system" to ",$sysA,,$sysB,")
        assert(canonical == interleaved) {
            "expected interleaved-blanks input to canonicalise; canonical=$canonical interleaved=$interleaved"
        }
        assert(interleaved.contains(compA)) { "expected $compA in $interleaved" }
        assert(interleaved.contains(compB)) { "expected $compB in $interleaved" }
        assert(!interleaved.contains(compC)) { "did not expect $compC in $interleaved" }
    }

    @Test
    @DisplayName("SYS-042: ?system=A%20 (trailing whitespace) matches A after trim")
    fun `SYS-042 system trailing whitespace trimmed`() {
        val sysA = uniqueSysCode("trimsysa")
        val tagged = uniqueName("systrim_tagged")
        createComponentWithSystem(tagged, sysA)

        val names = fetchNames("system" to "$sysA ")
        assert(names.contains(tagged)) { "expected $tagged in $names (trailing space should trim)" }
    }

    @Test
    @DisplayName("SYS-042: ?system=%20A (leading whitespace) matches A after trim")
    fun `SYS-042 system leading whitespace trimmed`() {
        val sysA = uniqueSysCode("ltrimsysa")
        val tagged = uniqueName("sysltrim_tagged")
        createComponentWithSystem(tagged, sysA)

        val names = fetchNames("system" to " $sysA")
        assert(names.contains(tagged)) { "expected $tagged in $names (leading space should trim)" }
    }

    @Test
    @DisplayName("SYS-042: ?system=A,A is equivalent to ?system=A (dedupe)")
    fun `SYS-042 system duplicate value collapsed`() {
        // Behavioural check on the controller's `distinct()` step: a
        // duplicated value must produce the same result set as a single
        // occurrence. IN(A, A) and IN(A) are set-theoretically identical;
        // dedupe keeps the predicate list tidy.
        val sysA = uniqueSysCode("dupsysa")
        val sysB = uniqueSysCode("dupsysb")
        val onlyA = uniqueName("sysdup_only_a")
        val onlyB = uniqueName("sysdup_only_b")
        createComponentWithSystem(onlyA, sysA)
        createComponentWithSystem(onlyB, sysB)

        val single = fetchNames("system" to sysA)
        val duplicated = fetchNames("system" to "$sysA,$sysA")
        assert(single == duplicated) {
            "expected ?system=A,A to match ?system=A; single=$single duplicated=$duplicated"
        }
        assert(duplicated.contains(onlyA)) { "expected $onlyA in $duplicated" }
        assert(!duplicated.contains(onlyB)) { "did not expect $onlyB in $duplicated" }
    }

    @Test
    @DisplayName("SYS-042: ?system=A,B combined with size + sort still paginates+sorts")
    fun `SYS-042 system with pagination and sort still applied`() {
        // Unique per-test system codes so totalElements over the OR set is
        // independent of other tests' fixtures, and predictable
        // lexicographic name prefixes for the sort-order assertion.
        val sysA = uniqueSysCode("pgnsysa")
        val sysB = uniqueSysCode("pgnsysb")
        val suffix = UUID.randomUUID().toString().take(6)
        val first = "syspg_aaa_$suffix"
        val second = "syspg_bbb_$suffix"
        val third = "syspg_ccc_$suffix"
        // Seed in reverse insertion order so a sort regression is visible.
        createComponentWithSystem(third, sysB)
        createComponentWithSystem(second, sysA)
        createComponentWithSystem(first, sysA)

        val pageBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("system", "$sysA,$sysB")
                        .param("size", "1")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn().response.contentAsString
        val pageJson = objectMapper.readTree(pageBody)
        assert(pageJson["content"].size() == 1) {
            "expected page size 1; got ${pageJson["content"].size()}: ${pageBody.take(400)}"
        }
        assert(pageJson["totalElements"].asLong() == 3L) {
            "expected totalElements=3 (three seeded sysA/sysB components); got ${pageJson["totalElements"]}"
        }

        val fullBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("system", "$sysA,$sysB")
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
