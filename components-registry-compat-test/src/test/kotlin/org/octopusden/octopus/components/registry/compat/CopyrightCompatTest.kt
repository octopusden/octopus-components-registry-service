package org.octopusden.octopus.components.registry.compat

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * GET /rest/api/3/components/{component}/copyright  (v3-only; binary octet-stream)
 * → `ResponseEntity<Resource>`, `Content-Disposition: attachment; filename=COPYRIGHT`
 * (`ComponentControllerV3.getCopyrightByComponent`).
 *
 * Closes part of #324: copyright has zero production-trace hits, so neither the
 * trace replay nor any other suite exercised it. Here it is exercised, but:
 *
 *  - the body is compared by **bytes**, not parsed (it is not JSON, so the raw
 *    layer's JSON-shape stage is a no-op — see `compareRaw`); and
 *  - on a mismatch ONLY the byte LENGTHS are recorded, never the content.
 *    COPYRIGHT files are licensee-identifying and must not leak into the
 *    (potentially public) compat report or CI logs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopyrightCompatTest : CompatibilityTestBase() {

    @ParameterizedTest(name = "GET /rest/api/3/components/{0}/copyright")
    @MethodSource("smokeComponentArgs")
    fun `GET v3 copyright must match per component (binary, content-redacted)`(componentName: String) {
        skipIfNoSmokeConfig(componentName)
        val endpoint = "GET /rest/api/3/components/{component}/copyright"
        val params = mapOf("component" to componentName)
        val (baseline, candidate) = fetchPair("/rest/api/3/components/$componentName/copyright")

        val before = DiffCollector.count()
        // Status + the two binary-relevant headers: Content-Type must stay
        // application/octet-stream, Content-Disposition must keep the attachment
        // filename. compareRaw does NOT compare the body here (both sides are
        // non-JSON, so its JSON-shape stage is a no-op) — hence the byte compare below.
        compareRaw(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            headerAllowList = setOf("Content-Type", "Content-Disposition"),
        )

        // Byte-level body parity, only when BOTH stands served the file (2xx).
        // A status divergence (e.g. one 200 / one 404) is already recorded by compareRaw.
        if (baseline.status in 200..299 &&
            candidate.status in 200..299 &&
            !baseline.bodyBytes.contentEquals(candidate.bodyBytes)
        ) {
            DiffCollector.record(
                DiffRecord(
                    ts = java.time.Instant.now().toString(),
                    endpoint = endpoint,
                    pathParams = params,
                    category = DiffClassifier.VALUE_DIFF,
                    layer = "raw-binary",
                    // Lengths ONLY — never the bytes (licensee-identifying).
                    baselineValue = "${baseline.bodyBytes.size} bytes",
                    candidateValue = "${candidate.bodyBytes.size} bytes",
                    message = "copyright payload bytes differ (content redacted)",
                ),
            )
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw-binary",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @AfterAll
    fun closeStreams() {
        DiffCollector.close()
        ExecutionLogger.close()
    }

    private fun smokeComponentArgs(): Stream<Arguments> = singleArgsOrSentinel(smokeComponents())
}
