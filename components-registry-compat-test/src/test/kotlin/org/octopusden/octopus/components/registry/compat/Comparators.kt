package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.RecursiveComparisonAssert
import java.time.Instant

/**
 * Pure-function compare layer for raw HTTP responses. Extracted out of
 * [CompatibilityTestBase] so its semantics can be unit-tested without the
 * HTTP/Spring scaffolding the base class needs at runtime.
 *
 * [CompatibilityTestBase.compareRaw] delegates here; every live-stand suite
 * hits the same code path.
 *
 * Effects: emits [DiffRecord]s into the process-wide [DiffCollector]. Callers
 * that need a side-effect-free comparison should not use this — instead, pass
 * the constructed inputs through the building blocks directly
 * ([JsonShape.diff], [RawResponse.stableHeaders]). The tests in
 * `ComparatorLogicTest` exercise the collector by snapshotting / clearing it
 * around each case.
 */
object Comparators {
    /**
     * Status -> header allow-list -> JSON-shape compare. Records every
     * divergence to [DiffCollector] and returns the categories observed.
     *
     * See [CompatibilityTestBase.compareRaw] for parameter semantics —
     * this object is the implementation, the method on the base class is
     * a thin delegate so existing call sites need no churn.
     */
    fun compareRaw(
        endpoint: String,
        pathParams: Map<String, String>,
        baseline: RawResponse,
        candidate: RawResponse,
        headerAllowList: Set<String> = setOf("Content-Type"),
        queryParams: Map<String, String> = emptyMap(),
    ): List<DiffClassifier> {
        val categories = mutableListOf<DiffClassifier>()
        val ts = Instant.now().toString()

        // status=0 is reserved by [RawHttpClient] for transport failures (timeout,
        // connection-refused, DNS, etc.). If both sides degrade to 0 the bare
        // `baseline.status != candidate.status` check passes silently and the run
        // looks clean — record a fail-causing diff whenever EITHER side is 0, even
        // if both are.
        val baselineTransportFailed = baseline.status == 0
        val candidateTransportFailed = candidate.status == 0
        if (baselineTransportFailed || candidateTransportFailed) {
            categories += DiffClassifier.STATUS_CODE_DIFF
            DiffCollector.record(
                DiffRecord(
                    ts = ts,
                    endpoint = endpoint,
                    pathParams = pathParams,
                    queryParams = queryParams,
                    category = DiffClassifier.STATUS_CODE_DIFF,
                    layer = "raw",
                    baselineValue = baseline.status.toString(),
                    candidateValue = candidate.status.toString(),
                    entityKey = CompatEntityContext.resolveEntityKey(endpoint, "", pathParams, null, null),
                    jsonPath = "$",
                    message = "transport failure on " + listOfNotNull(
                        "baseline".takeIf { baselineTransportFailed },
                        "candidate".takeIf { candidateTransportFailed },
                    ).joinToString(" and "),
                ),
            )
        } else if (baseline.status != candidate.status) {
            categories += DiffClassifier.STATUS_CODE_DIFF
            DiffCollector.record(
                DiffRecord(
                    ts = ts,
                    endpoint = endpoint,
                    pathParams = pathParams,
                    queryParams = queryParams,
                    category = DiffClassifier.STATUS_CODE_DIFF,
                    layer = "raw",
                    baselineValue = baseline.status.toString(),
                    candidateValue = candidate.status.toString(),
                    entityKey = CompatEntityContext.resolveEntityKey(endpoint, "", pathParams, null, null),
                    jsonPath = "$",
                ),
            )
        }

        val baselineHeaders = baseline.stableHeaders(headerAllowList)
        val candidateHeaders = candidate.stableHeaders(headerAllowList)
        for (key in (baselineHeaders.keys + candidateHeaders.keys).distinctBy { it.lowercase() }) {
            val bv = baselineHeaders[key]
            val cv = candidateHeaders[key]
            if (bv != cv) {
                categories += DiffClassifier.HEADER_DIFF
                DiffCollector.record(
                    DiffRecord(
                        ts = ts,
                        endpoint = endpoint,
                        pathParams = pathParams,
                        queryParams = queryParams,
                        category = DiffClassifier.HEADER_DIFF,
                        layer = "raw",
                        baselineValue = bv,
                        candidateValue = cv,
                        message = "header=$key",
                    ),
                )
            }
        }

        // Skip shape diffing if status codes already diverged or no JSON.
        if (baseline.status == candidate.status && baseline.json != null && candidate.json != null) {
            // For Set-shape endpoints (`/jira-component-version-ranges`, `/v3/components`)
            // the wire-order of elements is non-deterministic across stands. Pre-sort
            // both sides by a stable per-endpoint key so JsonShape.diff doesn't report
            // positional false-positives. Pass-through for unregistered endpoints
            // (see RawArraySorters and its unit test for the registered list + contract).
            val baselineForShape = RawArraySorters.stableSorted(endpoint, baseline.json)
            val candidateForShape = RawArraySorters.stableSorted(endpoint, candidate.json)
            val shapeDiffs = JsonShape.diff(baselineForShape, candidateForShape)
            for (sd in shapeDiffs) {
                categories += DiffClassifier.STRUCTURAL_DIFF
                val entityKey =
                    CompatEntityContext.resolveEntityKey(
                        endpoint = endpoint,
                        jsonPath = sd.path,
                        pathParams = pathParams,
                        baselineJson = baselineForShape,
                        candidateJson = candidateForShape,
                    )
                DiffCollector.record(
                    DiffRecord(
                        ts = ts,
                        endpoint = endpoint,
                        pathParams = pathParams,
                        queryParams = queryParams,
                        category = DiffClassifier.STRUCTURAL_DIFF,
                        layer = "raw",
                        baselineValue = sd.baseline,
                        candidateValue = sd.candidate,
                        entityKey = entityKey,
                        jsonPath = sd.path,
                        message = "${sd.kind} at ${sd.path}",
                    ),
                )
            }
        }

        return categories
    }

    /**
     * Typed-layer recursive DTO compare (AssertJ `usingRecursiveComparison`).
     * Extracted from [CompatibilityTestBase.compareDto] for the same reason
     * `compareRaw` was lifted: lets the per-field comparator wiring (below) be
     * unit-tested without the HTTP/Spring scaffolding.
     *
     * Records:
     *  - `NULL_VS_EMPTY` when exactly one side is null.
     *  - `VALUE_DIFF` when the recursive comparison fails — the full AssertJ
     *    description (including the diverging field path) is preserved as
     *    [DiffRecord.message] for diagnosis.
     *
     * Per-field normalizers installed here:
     *  - Any field whose root-relative path ends in `gav` routes through
     *    [GavCsvComparator] — see its KDoc for the rationale. Net effect:
     *    a single trailing-comma artefact in the CSV (otherwise identical
     *    content) does NOT surface as a VALUE_DIFF, but any change to the GAV
     *    set / ordering still does. The regex (rather than a literal field
     *    list) is intentional: `compareDto` is called with multiple root types
     *    across the suite — `Component` (path `distribution.gav`),
     *    `ComponentV3` (`component.distribution.gav`), `DistributionDTO` alone
     *    (`gav`), and `Map<String, DistributionDTO>` (`<projectKey>.gav`).
     *    All four paths share the `…gav` tail; a literal-list registration
     *    silently misses two of them (Opus Stage-2 finding on this PR).
     *    `gav` is the only field in the DTO graph that ends with this token,
     *    so the regex does not over-match.
     */
    fun <T : Any> compareDto(
        endpoint: String,
        pathParams: Map<String, String>,
        baseline: T?,
        candidate: T?,
        queryParams: Map<String, String> = emptyMap(),
    ) {
        if (baseline == null && candidate == null) return
        if (baseline == null || candidate == null) {
            DiffCollector.record(
                DiffRecord(
                    ts = Instant.now().toString(),
                    endpoint = endpoint,
                    pathParams = pathParams,
                    queryParams = queryParams,
                    category = DiffClassifier.NULL_VS_EMPTY,
                    layer = "typed",
                    baselineValue = baseline?.toString() ?: "null",
                    candidateValue = candidate?.toString() ?: "null",
                ),
            )
            return
        }
        runCatching {
            val assertion: RecursiveComparisonAssert<*> =
                org.assertj.core.api.Assertions
                    .assertThat(baseline)
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .withEqualsForFieldsMatchingRegexes(
                        // AssertJ 3.25.3 exposes a regex variant only for the
                        // BiPredicate form; wrap the shared Comparator so the
                        // normalization logic stays in one place.
                        java.util.function.BiPredicate<Any?, Any?> { a, b -> GavCsvComparator.compare(a, b) == 0 },
                        "^(.+\\.)?gav$",
                    )
            assertion.isEqualTo(candidate)
        }.onFailure { ex ->
            DiffCollector.record(
                DiffRecord(
                    ts = Instant.now().toString(),
                    endpoint = endpoint,
                    pathParams = pathParams,
                    queryParams = queryParams,
                    category = DiffClassifier.VALUE_DIFF,
                    layer = "typed",
                    message = ex.message,
                ),
            )
        }
    }
}

/**
 * Trailing-comma–tolerant comparator for the GAV-CSV string carried in
 * `Component.distribution.gav` (v1/v2) and `ComponentV3.component.distribution.gav`.
 *
 * **Why it exists.** The schema-v2 DB resolver emits the GAV list with a
 * trailing `,` after the last entry, while V1's in-memory resolver does not.
 * The trailing-comma is a pure formatting artefact — the underlying multiset
 * of GAV coordinates is identical. AssertJ's default `String.equals`
 * comparison surfaces it as a `VALUE_DIFF`, drowning out the OTHER real
 * regressions on the same response. This comparator normalises both sides
 * by stripping ONLY a trailing comma (with adjacent whitespace) and
 * comparing the result byte-for-byte.
 *
 * **What it does NOT do.**
 *  - Does NOT treat the field as a Set — element ORDER differences still
 *    surface as a `VALUE_DIFF`. V1's wire order is HashMap-iteration order
 *    so technically Set-shape, but unlike the list-of-components case
 *    (`RawArraySorters`) the GAV CSV is a single string field on one
 *    component, so the V2 DTO contract treats it as ordered. A future PR
 *    can switch to Set semantics if desired; do NOT widen this comparator
 *    silently.
 *  - Does NOT strip leading commas, internal whitespace, or duplicate
 *    entries — those would mask real bugs and are out of scope.
 *  - Does NOT touch other fields. The comparator is registered ONLY for the
 *    two known GAV-CSV field paths in [Comparators.compareDto].
 *
 * Returns `0` for "equal after trailing-comma strip", non-zero otherwise.
 * AssertJ's `withComparatorForFields` only uses the sign of the result to
 * decide equality, so the magnitude is irrelevant.
 */
object GavCsvComparator : Comparator<Any?> {
    override fun compare(a: Any?, b: Any?): Int {
        if (a == null && b == null) return 0
        if (a == null || b == null) return 1
        val normA = normalize(a.toString())
        val normB = normalize(b.toString())
        return normA.compareTo(normB)
    }

    /**
     * Strip a SINGLE trailing comma plus any whitespace that immediately
     * precedes / follows it. Multiple trailing commas (`",,"`) are intentionally
     * NOT collapsed — they would indicate a different bug shape and must
     * surface for diagnosis.
     */
    private fun normalize(s: String): String {
        val trimmed = s.trimEnd()
        return if (trimmed.endsWith(',')) trimmed.dropLast(1).trimEnd() else trimmed
    }
}
