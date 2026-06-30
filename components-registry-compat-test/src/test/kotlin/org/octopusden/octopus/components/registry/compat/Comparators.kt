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
            val baselineSorted = RawArraySorters.stableSorted(endpoint, baseline.json)
            val candidateSorted = RawArraySorters.stableSorted(endpoint, candidate.json)
            // ADR-018: the decoupled-model read path re-partitions version-range-keyed maps (`variants`,
            // `/maven-artifacts`) — whitespace, composite-split, adjacent-merge and version-form differ
            // from V1's verbatim DSL keys but describe the SAME (version → value) function. Canonicalise
            // BOTH sides so those reshapings don't read as STRUCTURAL_DIFF; a real coverage/value change
            // still surfaces (see VersionRangeMapCanonicalizer + its unit tests).
            val baselineForShape = VersionRangeMapCanonicalizer.normalizeForEndpoint(endpoint, baselineSorted)
            val candidateForShape = VersionRangeMapCanonicalizer.normalizeForEndpoint(endpoint, candidateSorted)
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
            var assertion: RecursiveComparisonAssert<*> =
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
            // #357: on /maven-artifacts the v1–v3 `artifactPattern` is re-rendered from the explicit
            // ownership model — separator (`,`≡`|`), dot-escaping, and ALL_EXCEPT lookahead-vs-catch-all
            // differ byte-wise but not behaviourally. Normalise ONLY here (the distribution
            // `artifactPattern` on other endpoints stays byte-faithful). See ArtifactPatternComparator.
            if (endpoint.contains("maven-artifacts")) {
                assertion =
                    assertion.withEqualsForFieldsMatchingRegexes(
                        java.util.function.BiPredicate<Any?, Any?> { a, b -> ArtifactPatternComparator.compare(a, b) == 0 },
                        "^(.+\\.)?artifactPattern$",
                    )
            }
            // ADR-018: the `variants` map (ComponentV3) is re-partitioned by the decoupled-model read path
            // (whitespace / composite-split / adjacent-merge / version-form) — same reshaping the raw layer
            // canonicalises. Compare it canonically so those don't read as a VALUE_DIFF; a real per-range
            // value change still surfaces (canonical maps differ). See VersionRangeMapCanonicalizer.
            assertion =
                assertion.withEqualsForFieldsMatchingRegexes(
                    java.util.function.BiPredicate<Any?, Any?> { a, b -> VersionRangeMapCanonicalizer.mapsEqualCanonically(a, b) },
                    "^(.+\\.)?variants$",
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

/**
 * Semantic comparator for the v1–v3 `ComponentArtifactConfigurationDTO.artifactPattern` string on
 * `GET /…/maven-artifacts` (#357 ownership rework).
 *
 * **Why it exists.** The legacy resolver stored each component's groupId/artifactId mapping as an
 * opaque regex string and emitted it verbatim. The schema-v3 explicit-ownership model
 * (EXPLICIT / ALL_EXCEPT_CLAIMED / ALL) tokenises that string and re-renders it, producing a
 * NORMALISED — not byte-identical — form. Three normalisations, each behaviour-preserving against
 * the legacy `MavenArtifactMatcher` (which does `Pattern.matches(pattern.replace(",", "|"), id)`):
 *  1. **Separator**: `a|b|c` ⇄ `a,b,c` — the matcher replaces `,`→`|`, so the two are identical.
 *  2. **Dot-escaping**: `com.foo` ⇄ `com\.foo` — for the real artifact IDs in the registry these
 *     match the same set (no artifact ID aliases a `.`-as-wildcard match).
 *  3. **ALL_EXCEPT_CLAIMED**: a negative-lookahead — legacy per-char `((?!sibling)[\w-\.])+` OR the
 *     anchored-exact `(?!(?:sibling)$)[\w-\.]+` form (the v4 export and the #357 Option A forward
 *     wire) — canonicalises to a catch-all bucket that PRESERVES its excluded-sibling SET
 *     (regardless of regex syntax / separator / escaping). So the SAME exclusion compared across
 *     stands is equal (incl. legacy-per-char ⇄ anchored-exact), but a plain
 *     catch-all (`ALL`, no exclusion) is NOT equated with `ALL_EXCEPT_CLAIMED[…]`, and two DIFFERENT
 *     exclusion sets are NOT equated. (A blanket catch-all⇄lookahead collapse would mask a real
 *     ownership change — e.g. prod excludes a sibling that a candidate over-claims.)
 *
 * Net effect: separator / escaping / regex-syntax differences of the SAME ownership do NOT surface,
 * but any change to the explicit token SET, the excluded-sibling SET, or catch-all-vs-explicit-list
 * STILL surfaces. Registered ONLY for the `…/maven-artifacts` endpoint (see [Comparators.compareDto])
 * so the byte-faithful comparison of the distribution `artifactPattern` is unaffected.
 *
 * Returns `0` for "equal after normalisation", non-zero otherwise (AssertJ uses only the sign).
 */
object ArtifactPatternComparator : Comparator<Any?> {
    // Catch-all bodies after escape-strip + lookahead-strip + paren-strip (the classifier's
    // KNOWN_CATCH_ALL forms, backslashes removed). A pattern reducing to one of these is the
    // "owns everything (modulo explicit siblings)" bucket — ALL or ALL_EXCEPT_CLAIMED.
    private val CATCH_ALL_CORES = setOf("[w-.]+", "[w-]+", "w+", ".*", "*")
    private const val CATCH_ALL = " CATCHALL"

    override fun compare(a: Any?, b: Any?): Int {
        if (a == null && b == null) return 0
        if (a == null || b == null) return 1
        return if (canonical(a.toString()) == canonical(b.toString())) 0 else 1
    }

    private fun canonical(raw: String): String {
        // Strip backslashes (\. -> .  escaped == literal for real artifact IDs), then NORMALISE the
        // anchored-exact-exclusion form `(?!(?:a|b)$)` down to the legacy per-char shape `(?!a|b)`
        // so the single `(?!…)` regex below extracts the same excluded SET from BOTH:
        //   legacy per-char:  ((?!sibling)[\w-\.])+
        //   anchored exact:   (?!(?:sibling)$)[\w-\.]+   ← the v4 export AND (#357 Option A) the
        //                                                  forward /maven-artifacts wire emit this
        // A TARGETED rewrite (not a blanket `(?:`/`$` strip): it only collapses the exact anchored
        // wrapper, so any unexpected pattern is left intact for the catch-all / token-list
        // canonicalisation below rather than silently mangled.
        val noEsc =
            raw.replace("\\", "")
                .replace(Regex("""\(\?!\(\?:([^)]*)\)\${'$'}\)""")) { "(?!${it.groupValues[1]})" }
        val lookaheadGroup = Regex("\\(\\?!([^)]*)\\)") // (?!…) — captures the exclusion body
        // A catch-all is the pattern reducing to a known catch-all body once its (?!…) exclusion
        // group(s) and wrapping parens are removed. PRESERVE the excluded-sibling SET in the bucket
        // so a plain catch-all (ALL) is NOT equated with ALL_EXCEPT_CLAIMED[…], and two different
        // exclusion sets are NOT equated (that would mask a real ownership change).
        val core =
            noEsc
                .replace(lookaheadGroup, "")
                .replace("(", "")
                .replace(")", "")
        if (core in CATCH_ALL_CORES) {
            val excluded =
                lookaheadGroup
                    .findAll(noEsc)
                    .flatMap { it.groupValues[1].split(Regex("[,|]")).asSequence() }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSortedSet()
            return if (excluded.isEmpty()) CATCH_ALL else "$CATCH_ALL EXCEPT[${excluded.joinToString(",")}]"
        }
        // Otherwise an explicit token list: separator-agnostic (`,`≡`|`). Order is PRESERVED
        // (a genuine reordering must still surface — same stance as GavCsvComparator).
        return noEsc.split(Regex("[,|]")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
    }
}
