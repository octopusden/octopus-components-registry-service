package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pure-unit coverage for [Comparators.compareRaw] — the raw-layer compare
 * entrypoint exercised by every live-stand suite via the delegator in
 * [CompatibilityTestBase].
 *
 * Pins the comparator semantics the addendum plan (TD-007 L1) calls out as
 * "could this test pass when production is silently wrong":
 *
 *  - status divergence short-circuits the shape walk (no spurious
 *    STRUCTURAL_DIFFs piled on top of a real STATUS_CODE_DIFF).
 *  - status=0 (transport failure) is itself a STATUS_CODE_DIFF — even when
 *    BOTH sides degrade to 0, the bare `baseline.status != candidate.status`
 *    equality would otherwise mask the failure.
 *  - the header allow-list compares the configured headers and ignores
 *    everything else, case-insensitively.
 *  - JSON-object key order is structurally insignificant (key permutation
 *    on baseline vs candidate is NOT a diff).
 *  - additive fields surface as KEY_MISSING_BASELINE / KEY_MISSING_CANDIDATE,
 *    via the [JsonShape] layer, with the path encoded into the message.
 *
 * `DiffCollector` is a process-singleton. Each test clears it first and then
 * inspects [DiffCollector.snapshot] after the call. The collector also writes
 * to `build/reports/compat/diff-worker-*.ndjson` as a side effect; that file
 * is recreated on each `:unitTest` run and is harmless test output.
 *
 * **Parallel-execution caveat:** the `clear()` + `snapshot()` pair is process-
 * wide. The `:unitTest` task (build.gradle) does NOT set
 * `junit.jupiter.execution.parallel.enabled`, so cases here run sequentially
 * within one JVM and there is no risk of cross-class pollution. If parallel
 * unit execution is ever enabled and another unit test class also writes to
 * `DiffCollector`, this assumption breaks — switch to a thread-local snapshot
 * helper at that point.
 */
@Tag("unit")
class ComparatorLogicTest {

    private val mapper = jacksonObjectMapper()
    private fun node(json: String): JsonNode = mapper.readTree(json)

    private fun response(
        status: Int = 200,
        body: String? = null,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    ): RawResponse =
        RawResponse(
            status = status,
            headers = headers,
            bodyBytes = (body ?: "").toByteArray(Charsets.UTF_8),
            json = body?.let { node(it) },
            durationMs = 0L,
        )

    @BeforeEach
    fun clearCollector() {
        DiffCollector.clear()
    }

    // ----- Status divergence short-circuits the shape walk -----

    @Test
    @DisplayName(
        "status divergence (200 vs 404) records STATUS_CODE_DIFF and does NOT pile on STRUCTURAL_DIFFs",
    )
    fun statusCodeDiffShortCircuitsShape() {
        val baseline = response(status = 200, body = """{"x":1}""")
        val candidate = response(status = 404, body = """{"errorMessage":"not found"}""")

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components/{c}",
            pathParams = mapOf("c" to "nonexistent"),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).containsExactly(DiffClassifier.STATUS_CODE_DIFF)
        val recorded = DiffCollector.snapshot()
        assertThat(recorded).hasSize(1)
        assertThat(recorded.single().category).isEqualTo(DiffClassifier.STATUS_CODE_DIFF)
        assertThat(recorded.single().baselineValue).isEqualTo("200")
        assertThat(recorded.single().candidateValue).isEqualTo("404")
    }

    @Test
    @DisplayName(
        "transport failure on candidate (status=0) → STATUS_CODE_DIFF with 'transport failure on candidate' message",
    )
    fun transportFailureCandidate() {
        val baseline = response(status = 200, body = """{"x":1}""")
        val candidate = response(status = 0, body = null)

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).containsExactly(DiffClassifier.STATUS_CODE_DIFF)
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.message).contains("transport failure", "candidate")
        assertThat(recorded.message).doesNotContain("baseline and")
    }

    @Test
    @DisplayName(
        "transport failure on BASELINE (status=0 on baseline, 200 on candidate) → STATUS_CODE_DIFF identifies baseline",
    )
    fun transportFailureBaseline() {
        // Symmetric counterpart to the candidate-only case. Without this assertion,
        // a regression that swapped the two `takeIf` predicates in the message
        // builder would pass every other test in this suite. The 'baseline'
        // branch must be exercised explicitly.
        val baseline = response(status = 0, body = null)
        val candidate = response(status = 200, body = """{"x":1}""")

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).containsExactly(DiffClassifier.STATUS_CODE_DIFF)
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.message).contains("transport failure", "baseline")
        // The "and candidate" suffix must NOT appear — only baseline failed here.
        assertThat(recorded.message).doesNotContain("and candidate")
    }

    @Test
    @DisplayName(
        "transport failure on BOTH sides (status=0/0) still records STATUS_CODE_DIFF — bare equality check would otherwise mask this",
    )
    fun transportFailureBothSides() {
        val baseline = response(status = 0, body = null)
        val candidate = response(status = 0, body = null)

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        // The naive `baseline.status != candidate.status` check would pass
        // here (0 == 0). The OR-branch on either side being 0 catches it.
        assertThat(categories).containsExactly(DiffClassifier.STATUS_CODE_DIFF)
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.message).contains("transport failure", "baseline and candidate")
    }

    // ----- Shape semantics -----

    @Test
    @DisplayName("clean run: identical body + identical Content-Type → zero diffs recorded")
    fun cleanRunRecordsNothing() {
        val body = """{"a":1,"b":"two"}"""
        val baseline = response(body = body)
        val candidate = response(body = body)

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components/{c}",
            pathParams = mapOf("c" to "foo"),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).isEmpty()
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("same body with permuted object key order — zero diffs")
    fun keyOrderIsNotADiff() {
        val baseline = response(body = """{"a":1,"b":"two","c":[1,2,3]}""")
        val candidate = response(body = """{"c":[1,2,3],"b":"two","a":1}""")

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components/{c}",
            pathParams = mapOf("c" to "foo"),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).isEmpty()
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("additive field on candidate → STRUCTURAL_DIFF with KEY_MISSING_BASELINE in message")
    fun additiveFieldOnCandidateIsAStructuralDiff() {
        val baseline = response(body = """{"x":1}""")
        val candidate = response(body = """{"x":1,"newField":"hello"}""")

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).containsExactly(DiffClassifier.STRUCTURAL_DIFF)
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.category).isEqualTo(DiffClassifier.STRUCTURAL_DIFF)
        assertThat(recorded.message).contains("KEY_MISSING_BASELINE", "newField")
    }

    @Test
    @DisplayName("array of different sizes at same status → STRUCTURAL_DIFF with ARRAY_SIZE_MISMATCH in message")
    fun arraySizeMismatchSurfacesViaStructuralDiff() {
        // Exercises the JsonShape array-walk branch — not covered by the other
        // shape cases, which all use object roots. Pinning this case is the
        // regression guard against accidentally short-circuiting array diffs
        // (e.g. by mis-passing the body through RawArraySorters which would
        // align same-sized arrays but should NOT mask different-sized arrays).
        val baseline = response(body = """{"items":[1,2,3]}""")
        val candidate = response(body = """{"items":[1,2]}""")

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).containsExactly(DiffClassifier.STRUCTURAL_DIFF)
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.message).contains("ARRAY_SIZE_MISMATCH")
        assertThat(recorded.baselineValue).isEqualTo("3")
        assertThat(recorded.candidateValue).isEqualTo("2")
    }

    @Test
    @DisplayName("removed field on candidate → STRUCTURAL_DIFF with KEY_MISSING_CANDIDATE in message")
    fun removedFieldOnCandidateIsAStructuralDiff() {
        val baseline = response(body = """{"x":1,"oldField":"hello"}""")
        val candidate = response(body = """{"x":1}""")

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).containsExactly(DiffClassifier.STRUCTURAL_DIFF)
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.message).contains("KEY_MISSING_CANDIDATE", "oldField")
    }

    // ----- Header allow-list semantics -----

    @Test
    @DisplayName("Content-Type difference → HEADER_DIFF (with the header key in the message)")
    fun contentTypeMismatchIsAHeaderDiff() {
        val baseline = response(
            body = """{"x":1}""",
            headers = mapOf("Content-Type" to "application/json"),
        )
        val candidate = response(
            body = """{"x":1}""",
            headers = mapOf("Content-Type" to "text/plain"),
        )

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).contains(DiffClassifier.HEADER_DIFF)
        val headerRec = DiffCollector.snapshot().single { it.category == DiffClassifier.HEADER_DIFF }
        // `stableHeaders` normalizes to lowercase keys, so the message echoes
        // `header=content-type` regardless of how the wire spelled it.
        assertThat(headerRec.message).contains("content-type")
        assertThat(headerRec.baselineValue).isEqualTo("application/json")
        assertThat(headerRec.candidateValue).isEqualTo("text/plain")
    }

    @Test
    @DisplayName("Date / ETag / Server differ but are not in the allow-list → no HEADER_DIFF")
    fun outOfAllowListHeadersAreIgnored() {
        val baseline = response(
            body = """{"x":1}""",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Date" to "Mon, 16 May 2026 10:00:00 GMT",
                "ETag" to "abc123",
                "Server" to "nginx/1.21",
            ),
        )
        val candidate = response(
            body = """{"x":1}""",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Date" to "Mon, 16 May 2026 10:00:42 GMT",
                "ETag" to "def456",
                "Server" to "tomcat/10.0",
            ),
        )

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).isEmpty()
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("Header allow-list is case-insensitive: baseline 'Content-Type' vs candidate 'content-type' → no HEADER_DIFF when values match")
    fun headerAllowListIsCaseInsensitive() {
        val baseline = response(
            body = """{"x":1}""",
            headers = mapOf("Content-Type" to "application/json"),
        )
        val candidate = response(
            body = """{"x":1}""",
            // Same value, but the key has different casing — the comparator
            // must normalize before comparing or it produces a spurious diff.
            headers = mapOf("content-type" to "application/json"),
        )

        val categories = Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = baseline,
            candidate = candidate,
        )

        assertThat(categories).isEmpty()
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    // ----- Plumbing: pathParams + queryParams thread through to the record -----

    @Test
    @DisplayName("recorded DiffRecord carries the endpoint + path + queryParams the caller passed")
    fun diffRecordCarriesContext() {
        val baseline = response(status = 200, body = """{"x":1}""")
        val candidate = response(status = 500, body = """{"errorMessage":"boom"}""")

        Comparators.compareRaw(
            endpoint = "GET /rest/api/2/components/{c}/build-tools",
            pathParams = mapOf("c" to "alpha"),
            baseline = baseline,
            candidate = candidate,
            queryParams = mapOf("ignore-required" to "true"),
        )

        val r = DiffCollector.snapshot().single()
        assertThat(r.endpoint).isEqualTo("GET /rest/api/2/components/{c}/build-tools")
        assertThat(r.pathParams).containsEntry("c", "alpha")
        assertThat(r.queryParams).containsEntry("ignore-required", "true")
    }

    // ----- GavCsvComparator: trailing-comma tolerance + anti-regression -----

    @Test
    @DisplayName("GavCsvComparator: identical CSVs → 0")
    fun gavCsvComparator_identical_returnsZero() {
        assertThat(GavCsvComparator.compare("a:b:zip,c:d:zip", "a:b:zip,c:d:zip")).isEqualTo(0)
    }

    @Test
    @DisplayName("GavCsvComparator: trailing comma only — treated as equal (cosmetic)")
    fun gavCsvComparator_trailingCommaIsIgnored() {
        assertThat(GavCsvComparator.compare("a:b:zip,c:d:zip,", "a:b:zip,c:d:zip")).isEqualTo(0)
        // Symmetric: candidate without trailing comma, baseline with.
        assertThat(GavCsvComparator.compare("a:b:zip,c:d:zip", "a:b:zip,c:d:zip,")).isEqualTo(0)
    }

    @Test
    @DisplayName("GavCsvComparator: trailing whitespace around the comma is tolerated")
    fun gavCsvComparator_trailingWhitespaceAroundComma() {
        assertThat(GavCsvComparator.compare("a:b:zip,c:d:zip,  ", "a:b:zip,c:d:zip")).isEqualTo(0)
        assertThat(GavCsvComparator.compare("a:b:zip,c:d:zip ,", "a:b:zip,c:d:zip")).isEqualTo(0)
    }

    @Test
    @DisplayName("anti-regression: GAV with a DIFFERENT artifactId still produces a non-zero diff")
    fun gavCsvComparator_differentArtifactIdSurvives() {
        // The `ssd-static` vs `ssd-interactive` family in the prod residual:
        // wholly different artifactId. Must NOT be masked.
        val cmp = GavCsvComparator.compare(
            "com.example.foo:product-alpha:zip",
            "com.example.foo:product-beta:zip",
        )
        assertThat(cmp).isNotEqualTo(0)
    }

    @Test
    @DisplayName("anti-regression: GAV with the same set but DIFFERENT order surfaces a non-zero diff")
    fun gavCsvComparator_reorderingSurvives() {
        // The DSL declaration order is part of the V1 contract — this comparator
        // intentionally does NOT treat the CSV as a Set.
        val cmp = GavCsvComparator.compare("a:b:zip,c:d:zip", "c:d:zip,a:b:zip")
        assertThat(cmp).isNotEqualTo(0)
    }

    @Test
    @DisplayName("anti-regression: a leading comma is NOT stripped — surface for diagnosis")
    fun gavCsvComparator_leadingCommaSurvives() {
        // KDoc explicitly claims `does NOT strip leading commas`. Pin it so a
        // future widening of `normalize(...)` to `trim(',')` is caught.
        val cmp = GavCsvComparator.compare(",a:b:zip,c:d:zip", "a:b:zip,c:d:zip")
        assertThat(cmp).isNotEqualTo(0)
    }

    @Test
    @DisplayName("anti-regression: internal whitespace inside the CSV is NOT collapsed")
    fun gavCsvComparator_internalWhitespaceSurvives() {
        // KDoc explicitly claims `does NOT strip internal whitespace`. Pin it.
        val cmp = GavCsvComparator.compare("a:b:zip, c:d:zip", "a:b:zip,c:d:zip")
        assertThat(cmp).isNotEqualTo(0)
    }

    @Test
    @DisplayName("anti-regression: multiple trailing commas (',,') are NOT collapsed — surface for diagnosis")
    fun gavCsvComparator_doubleTrailingCommaSurvives() {
        // A single trailing comma is a known schema-v2 cosmetic; two suggest
        // a different bug shape (empty element in the middle?) and must remain
        // visible.
        val cmp = GavCsvComparator.compare("a:b:zip,c:d:zip,,", "a:b:zip,c:d:zip")
        assertThat(cmp).isNotEqualTo(0)
    }

    @Test
    @DisplayName("GavCsvComparator: null handling — null,null is equal; null,X is not")
    fun gavCsvComparator_nullHandling() {
        assertThat(GavCsvComparator.compare(null, null)).isEqualTo(0)
        assertThat(GavCsvComparator.compare(null, "a:b:zip")).isNotEqualTo(0)
        assertThat(GavCsvComparator.compare("a:b:zip", null)).isNotEqualTo(0)
    }

    // ----- compareDto integration: GAV normalizer applied to distribution.gav -----

    // Local synthetic DTO mirroring the shape `Component { distribution: Distribution { gav: String } }`.
    // Avoids depending on the production ComponentV2 type so the test stays focused on the comparator,
    // and uses `com.example.*` GAVs throughout (no real internal identifiers).
    data class TestDistribution(val gav: String?)
    data class TestComponent(val id: String, val distribution: TestDistribution)

    @Test
    @DisplayName("compareDto: trailing-comma-only diff on distribution.gav → NO VALUE_DIFF recorded")
    fun compareDto_trailingCommaOnDistributionGav_isSilenced() {
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components",
            pathParams = mapOf("componentId" to "alpha-fixture"),
            baseline = TestComponent("alpha-fixture", TestDistribution("com.example.foo:art-a:zip,com.example.foo:art-b:zip")),
            candidate = TestComponent("alpha-fixture", TestDistribution("com.example.foo:art-a:zip,com.example.foo:art-b:zip,")),
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("compareDto: a real GAV-value change on distribution.gav STILL records VALUE_DIFF")
    fun compareDto_realGavChangeOnDistributionGav_surfaces() {
        // ssd-static vs ssd-interactive shape from the prod residual — same number of
        // entries, different artifactId. Must NOT be masked.
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components",
            pathParams = mapOf("componentId" to "beta-fixture"),
            baseline = TestComponent("beta-fixture", TestDistribution("com.example.foo:product-alpha:zip")),
            candidate = TestComponent("beta-fixture", TestDistribution("com.example.foo:product-beta:zip")),
        )
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.category).isEqualTo(DiffClassifier.VALUE_DIFF)
        assertThat(recorded.message).contains("distribution.gav")
    }

    @Test
    @DisplayName("compareDto: trailing-comma silenced when root IS the DistributionDTO directly (path == \"gav\")")
    fun compareDto_trailingCommaOnRootGavField_isSilenced() {
        // Pins that the regex `^(.+\.)?gav$` matches the bare-root path too,
        // not just `<something>.gav`. The endpoint
        // `GET /v2/projects/{p}/versions/{v}/distribution` returns a single
        // `DistributionDTO` — AssertJ's path for `gav` from that root is just
        // `gav`, no prefix.
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/projects/{p}/versions/{v}/distribution",
            pathParams = mapOf("p" to "alpha-project", "v" to "1.0"),
            baseline = TestDistribution("com.example.foo:art-a:zip,com.example.foo:art-b:zip"),
            candidate = TestDistribution("com.example.foo:art-a:zip,com.example.foo:art-b:zip,"),
        )
        assertThat(DiffCollector.snapshot()).isEmpty()
    }

    @Test
    @DisplayName("compareDto: a diff on a DIFFERENT String field (not distribution.gav) is NOT routed through the normalizer")
    fun compareDto_normalizerScopedToGavField() {
        // Verify the comparator was registered ONLY for the GAV field path.
        // `id` differs by a trailing comma — must still surface as a VALUE_DIFF
        // because the comparator must not apply to arbitrary String fields.
        data class IdHolder(val id: String, val distribution: TestDistribution)
        Comparators.compareDto(
            endpoint = "GET /rest/api/2/components",
            pathParams = emptyMap(),
            baseline = IdHolder("alpha-fixture", TestDistribution("com.example.foo:art:zip")),
            candidate = IdHolder("alpha-fixture,", TestDistribution("com.example.foo:art:zip")),
        )
        val recorded = DiffCollector.snapshot().single()
        assertThat(recorded.category).isEqualTo(DiffClassifier.VALUE_DIFF)
        assertThat(recorded.message).contains("id")
    }
}
