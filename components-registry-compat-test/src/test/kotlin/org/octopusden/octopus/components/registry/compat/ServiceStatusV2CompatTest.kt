package org.octopusden.octopus.components.registry.compat

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Service-management endpoints under /rest/api/2/components-registry/service that are
 * part of the compat surface.
 *
 * - ping        → plain text "Pong" (not JSON); only raw status + body diff.
 * - updateCache → PUT, phase-aware on the candidate. In db-mode / [1.7] the candidate
 *                 is fully migrated so it returns 410 Gone (STATUS_CODE_DIFF suppressed
 *                 via known-deltas-db.json). In git-mode / [1.8] it returns 200 + a
 *                 re-read like the baseline; that heavy re-parse is flaky under parallel
 *                 load, so this mutating endpoint is EXCLUDED from the git-mode
 *                 read-parity surface (its git-mode 200 behaviour is unit/integration-
 *                 tested in the server module) — keeping the empty git-mode deltas valid.
 *
 * /service/status is intentionally NOT a compat-surface endpoint: it is operational
 * metadata (transient cacheUpdatedAt timestamp) read only by [SnapshotPreconditionTest]
 * for environment preconditions (versionControlRevision + serviceMode).
 * See docs/registry/api-compat-deltas.md §"Compat surface scope".
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
        // git-mode (id18 / [1.8]): the candidate is git-routed, so updateCache returns
        // 200 + a re-read just like the baseline. That re-read is a heavy full re-parse
        // that can transport-fail on either stand under parallel load — an environmental
        // flake that would be an UNSUPPRESSABLE STATUS_CODE_DIFF against the intentionally
        // empty git-mode deltas. The git-mode 200 contract is pinned by
        // ComponentsRegistryServiceControllerUpdateCacheTest + the server integration test,
        // so exclude this mutating endpoint from the git-mode read-parity surface.
        assumeTrue(
            !config.gitMode,
            "updateCache excluded in git-mode (heavy/flaky re-parse; git-mode 200 behaviour is unit/integration-tested)",
        )
        val endpoint = "PUT /rest/api/2/components-registry/service/updateCache"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = putPair("/rest/api/2/components-registry/service/updateCache")

        val before = DiffCollector.count()
        // db-mode (id17 / [1.7]): baseline (v2) returns 200; candidate (v3) is fully
        // migrated so updateCache returns 410 Gone — see ComponentsRegistryServiceController.
        // compareRaw records STATUS_CODE_DIFF; the reporter matches it against the
        // updateCache entry in known-deltas-db.json and moves it to the Suppressed section.
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
