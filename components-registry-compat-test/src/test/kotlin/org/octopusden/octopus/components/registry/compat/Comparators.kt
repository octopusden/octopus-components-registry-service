package org.octopusden.octopus.components.registry.compat

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
                        message = "${sd.kind} at ${sd.path}",
                    ),
                )
            }
        }

        return categories
    }
}
