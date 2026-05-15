package org.octopusden.octopus.components.registry.compat

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Service-management endpoints under /rest/api/2/components-registry/service that are
 * part of the compat surface.
 *
 * - ping        → plain text "Pong" (not JSON); only raw status + body diff.
 * - updateCache → PUT, expect HTTP 410 Gone on candidate (v3 deprecates the legacy
 *                 endpoint); STATUS_CODE_DIFF here is suppressed via known-deltas.json.
 *
 * /service/status is intentionally NOT a compat-surface endpoint: it is operational
 * metadata (transient cacheUpdatedAt timestamp) read only by [SnapshotPreconditionTest]
 * for environment preconditions (versionControlRevision + serviceMode).
 * See docs/db-migration/api-compat-deltas.md §"Compat surface scope".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceStatusV2CompatTest : CompatibilityTestBase() {

    @Test
    fun `GET ping must return Pong on both stands`() {
        val endpoint = "GET /rest/api/2/components-registry/service/ping"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/2/components-registry/service/ping")

        val before = DiffCollector.count()
        // ping returns plain text — compareRaw handles status + header diffs;
        // no JSON shape to diff (json field will be null for non-JSON response).
        compareRaw(endpoint, params, baseline, candidate)

        // Record a body diff if the text differs between stands.
        val baselineBody = baseline.bodyText().trim()
        val candidateBody = candidate.bodyText().trim()
        if (baselineBody != candidateBody) {
            DiffCollector.record(
                DiffRecord(
                    ts = java.time.Instant.now().toString(),
                    endpoint = endpoint,
                    pathParams = params,
                    category = DiffClassifier.VALUE_DIFF,
                    layer = "raw",
                    baselineValue = baselineBody,
                    candidateValue = candidateBody,
                    message = "ping body text differs",
                ),
            )
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @Test
    fun `PUT updateCache records 200 vs 410 status-code delta (suppressed via known-deltas)`() {
        val endpoint = "PUT /rest/api/2/components-registry/service/updateCache"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = putPair("/rest/api/2/components-registry/service/updateCache")

        val before = DiffCollector.count()
        // baseline (v2) returns 200; candidate (v3) returns 410 Gone — legacy endpoint
        // deprecated, see ComponentsRegistryServiceController. compareRaw records
        // STATUS_CODE_DIFF; the reporter then matches it against the C.1 known-delta
        // entry in known-deltas.json and moves it to the Suppressed section.
        // Body shape on 410 may legitimately vary, so no typed decode.
        compareRaw(endpoint, params, baseline, candidate)

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @AfterAll
    fun closeStreams() {
        DiffCollector.close()
        ExecutionLogger.close()
    }
}
