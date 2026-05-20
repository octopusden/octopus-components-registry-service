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
 * GET /rest/api/4/components?labels=A,B filters the list by the
 * component_labels junction. Multi-value semantics is AND across the
 * selections: a component must carry every selected label to be returned.
 *
 * Wire format primary is CSV (?labels=A,B); Spring's binder also accepts
 * repeatable params (?labels=A&labels=B). The controller normalises both
 * via split-by-comma → trim → drop-empty → null-if-empty, so blank or
 * whitespace-only entries collapse to "no filter" instead of producing an
 * empty-string predicate that silently empties the page.
 *
 * Closest existing analogue is [ListComponentsSystemFilterTest] — system is
 * also a junction-backed multi-value filter, but with single-value
 * ?system=... semantics; labels extends that template with CSV + AND.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class ListComponentsLabelsFilterTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    init {
        val testResourcesPath =
            Paths.get(ListComponentsLabelsFilterTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    private fun uniqueName(prefix: String) = "${prefix}_${UUID.randomUUID().toString().take(8)}"

    private fun createComponentWithLabels(
        name: String,
        labels: Set<String>,
    ) {
        val labelsJson = labels.joinToString(",") { "\"$it\"" }
        mvc
            .perform(
                post("/rest/api/4/components")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"$name","displayName":"$name","labels":[$labelsJson]}"""),
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
    @DisplayName("SYS-040: ?labels=A returns only components carrying label A")
    fun `SYS-040 labels A returns components carrying label A`() {
        val labelA = uniqueName("lbla")
        val labelB = uniqueName("lblb")
        val onlyA = uniqueName("lbl_only_a")
        val onlyB = uniqueName("lbl_only_b")
        val both = uniqueName("lbl_both")

        createComponentWithLabels(onlyA, setOf(labelA))
        createComponentWithLabels(onlyB, setOf(labelB))
        createComponentWithLabels(both, setOf(labelA, labelB))

        val names = fetchNames("labels" to labelA)
        assert(names.contains(onlyA)) { "expected $onlyA in $names" }
        assert(names.contains(both)) { "expected $both in $names" }
        assert(!names.contains(onlyB)) { "did not expect $onlyB in $names" }
    }

    @Test
    @DisplayName("SYS-040: ?labels=A,B applies AND semantics across selected labels")
    fun `SYS-040 labels A,B AND semantics`() {
        val labelA = uniqueName("andlbla")
        val labelB = uniqueName("andlblb")
        val onlyA = uniqueName("and_only_a")
        val onlyB = uniqueName("and_only_b")
        val both = uniqueName("and_both")
        // Superset case: a component carrying {A, B, C} must also match
        // ?labels=A,B. The per-join + distinct Specification is correct by
        // construction, but without this assertion a future regression to a
        // single-join + IN(...)-of-the-list (which would silently relax to
        // OR) wouldn't be caught by the {A}/{B}/{A,B} cases alone.
        val superComponentName = uniqueName("and_super")

        createComponentWithLabels(onlyA, setOf(labelA))
        createComponentWithLabels(onlyB, setOf(labelB))
        createComponentWithLabels(both, setOf(labelA, labelB))
        createComponentWithLabels(superComponentName, setOf(labelA, labelB, uniqueName("extra")))

        val names = fetchNames("labels" to "$labelA,$labelB")
        assert(names.contains(both)) { "expected $both in $names (carries both labels)" }
        assert(names.contains(superComponentName)) {
            "expected $superComponentName in $names (carries A, B, and an extra label)"
        }
        assert(!names.contains(onlyA)) { "did not expect $onlyA in $names (only carries A)" }
        assert(!names.contains(onlyB)) { "did not expect $onlyB in $names (only carries B)" }
    }

    @Test
    @DisplayName("SYS-040: ?labels=A,A is equivalent to ?labels=A (duplicates collapsed)")
    fun `SYS-040 labels A,A is equivalent to labels A`() {
        // Behavioural check on the controller's `distinct()` normalisation
        // step: a duplicated code in the query string must produce the same
        // result set as a single occurrence. Without dedupe the
        // Specification would still match the same components (AND of
        // identical predicates is a no-op set-theoretically), but it would
        // issue a redundant extra JOIN. We assert behaviour parity, which
        // is the externally observable guarantee; the JOIN-count delta is
        // an internal optimisation.
        val labelA = uniqueName("duplbla")
        val labelB = uniqueName("duplblb")
        val onlyA = uniqueName("dup_only_a")
        val onlyB = uniqueName("dup_only_b")
        createComponentWithLabels(onlyA, setOf(labelA))
        createComponentWithLabels(onlyB, setOf(labelB))

        val singleNames = fetchNames("labels" to labelA)
        val duplicatedNames = fetchNames("labels" to "$labelA,$labelA")
        assert(singleNames == duplicatedNames) {
            "expected ?labels=A,A to match ?labels=A; single=$singleNames duplicated=$duplicatedNames"
        }
        assert(duplicatedNames.contains(onlyA)) { "expected $onlyA in $duplicatedNames" }
        assert(!duplicatedNames.contains(onlyB)) { "did not expect $onlyB in $duplicatedNames" }
    }

    @Test
    @DisplayName("SYS-040: ?labels=<unknown> returns empty page (not 500)")
    fun `SYS-040 labels unknown returns empty page`() {
        val unknown = uniqueName("unknown_label")
        mvc
            .perform(
                get("/rest/api/4/components")
                    .with(viewerJwt())
                    .param("labels", unknown),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    @DisplayName("SYS-040: ?labels= (blank) is equivalent to no labels filter")
    fun `SYS-040 labels blank value is no filter`() {
        // Seed a component to ensure the unfiltered page has content (so the
        // assertion "blank == no filter" is meaningful even on a fresh DB).
        val seed = uniqueName("blank_seed")
        createComponentWithLabels(seed, setOf(uniqueName("seedlbl")))

        val withoutParam = fetchNames()
        val withBlank = fetchNames("labels" to "")
        assert(withoutParam == withBlank) {
            "expected ?labels= to match no filter; without=$withoutParam blank=$withBlank"
        }
    }

    @Test
    @DisplayName("SYS-040: ?labels=,, (only blanks) is equivalent to no labels filter")
    fun `SYS-040 labels only blanks is no filter`() {
        val seed = uniqueName("commas_seed")
        createComponentWithLabels(seed, setOf(uniqueName("seedlbl2")))

        val withoutParam = fetchNames()
        val withCommas = fetchNames("labels" to ",,")
        assert(withoutParam == withCommas) {
            "expected ?labels=,, to match no filter; without=$withoutParam commas=$withCommas"
        }
    }

    @Test
    @DisplayName("SYS-040: ?labels=,A,,B, behaves as ?labels=A,B (interleaved blanks dropped)")
    fun `SYS-040 labels interleaved blanks normalised`() {
        val labelA = uniqueName("ilvlbla")
        val labelB = uniqueName("ilvlblb")
        val onlyA = uniqueName("ilv_only_a")
        val both = uniqueName("ilv_both")

        createComponentWithLabels(onlyA, setOf(labelA))
        createComponentWithLabels(both, setOf(labelA, labelB))

        val canonical = fetchNames("labels" to "$labelA,$labelB")
        val interleaved = fetchNames("labels" to ",$labelA,,$labelB,")
        assert(canonical == interleaved) {
            "expected interleaved-blanks input to canonicalise; canonical=$canonical interleaved=$interleaved"
        }
        assert(interleaved.contains(both)) { "expected $both in $interleaved" }
        assert(!interleaved.contains(onlyA)) { "did not expect $onlyA in $interleaved (lacks B)" }
    }

    @Test
    @DisplayName("SYS-040: ?labels=A%20 (trailing whitespace) matches label A after trim")
    fun `SYS-040 labels trailing whitespace trimmed`() {
        val labelA = uniqueName("trimlbla")
        val tagged = uniqueName("trim_tagged")
        createComponentWithLabels(tagged, setOf(labelA))

        val names = fetchNames("labels" to "$labelA ")
        assert(names.contains(tagged)) { "expected $tagged in $names (trailing space should trim)" }
    }

    @Test
    @DisplayName("SYS-040: ?labels=%20A (leading whitespace) matches label A after trim")
    fun `SYS-040 labels leading whitespace trimmed`() {
        val labelA = uniqueName("ltrimlbla")
        val tagged = uniqueName("ltrim_tagged")
        createComponentWithLabels(tagged, setOf(labelA))

        val names = fetchNames("labels" to " $labelA")
        assert(names.contains(tagged)) { "expected $tagged in $names (leading space should trim)" }
    }

    @Test
    @DisplayName("SYS-040: ?labels=A combined with size=N + sort=componentKey,asc still paginates+sorts")
    fun `SYS-040 labels with pagination and sort still applied`() {
        // Predictable lexicographic prefixes (aaa < bbb < ccc) so the
        // sort-order assertion below is unambiguous. The UUID suffix
        // disambiguates parallel test runs in the same Spring context but
        // does not influence the leading aaa/bbb/ccc ordering.
        val labelA = uniqueName("pgnlbla")
        val suffix = UUID.randomUUID().toString().take(6)
        val first = "pgn_aaa_$suffix"
        val second = "pgn_bbb_$suffix"
        val third = "pgn_ccc_$suffix"
        // Seed in reverse lexicographic order so a regression where sort
        // silently fails (e.g., returning insertion order) is visibly wrong
        // in the asserted content[].name array.
        createComponentWithLabels(third, setOf(labelA))
        createComponentWithLabels(second, setOf(labelA))
        createComponentWithLabels(first, setOf(labelA))

        // First: assert page=0, size=1 returns exactly one entry and
        // totalElements=3. This is the original pagination regression
        // signal — the labels filter must not break the page metadata.
        val pageBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("labels", labelA)
                        .param("size", "1")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val pageJson = objectMapper.readTree(pageBody)
        val pageContent = pageJson["content"]
        assert(pageContent.size() == 1) {
            "expected page size 1; got ${pageContent.size()}: ${pageBody.take(400)}"
        }
        assert(pageJson["totalElements"].asLong() == 3L) {
            "expected totalElements=3 (three seeded labelled components); got ${pageJson["totalElements"]}"
        }

        // Second: fetch all three on a single page and assert the returned
        // content[].name array is sorted ascending. A regression where the
        // sort silently no-ops (e.g., the labels join clobbered the order
        // by) would surface as out-of-order names; the size+totalElements
        // assertions above wouldn't catch that.
        val fullBody =
            mvc
                .perform(
                    get("/rest/api/4/components")
                        .with(viewerJwt())
                        .param("labels", labelA)
                        .param("size", "10")
                        .param("sort", "componentKey,asc"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val fullJson = objectMapper.readTree(fullBody)
        val returnedNames = fullJson["content"].map { it["name"].asText() }
        val seededNames = returnedNames.filter { it.endsWith("_$suffix") }
        assert(seededNames == listOf(first, second, third)) {
            "expected components returned sorted by componentKey ASC ($first, $second, $third); got $seededNames"
        }
    }
}
