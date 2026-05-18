package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/**
 * Pre-flight environment check: read /service/status from both stands and record
 * env-level diffs as [DiffRecord]s with category [DiffClassifier.SNAPSHOT_MISMATCH]
 * or [DiffClassifier.CANDIDATE_NOT_DB_MODE] so they appear at the top of
 * `summary.md` and explain downstream divergences.
 *
 * The test method itself ALWAYS passes — it only records [DiffRecord]s. The Gradle
 * `compatibilityReporter` task aggregates them; env categories are surfaced
 * separately and are NOT suppressible via `known-deltas.json` (they signal that
 * the comparison itself is unsound, not an intentional v3 delta).
 *
 * Why recorded diffs and not fail-fast assertions: per operator decision, we want
 * the full compat surface to run even when stands disagree on snapshot or when
 * the candidate is misconfigured, so all real divergences are visible in one
 * report. The environment warnings are just *more records* in summary.md,
 * surfaced at the top.
 *
 * Two env signals are recorded:
 * 1. [DiffClassifier.SNAPSHOT_MISMATCH] — baseline and candidate point at different
 *    VCS revisions; data drift between snapshots cannot be distinguished from
 *    migration regressions.
 * 2. [DiffClassifier.CANDIDATE_NOT_DB_MODE] — candidate's `/service/status` reports
 *    `defaultSource != "db"` OR `dbComponentCount == 0` (or both fields null —
 *    candidate predates the diagnostic-field PR). The schema-v2 `DatabaseComponent-
 *    RegistryResolver` is dormant and the candidate is serving V1 in-memory data;
 *    any diff measurements are V1-vs-V1 drift, not real schema-v2-vs-V1 deltas.
 *    Note that `ServiceStatusDTO.serviceMode {FS, VCS}` is NOT a sufficient signal
 *    on its own (it tracks `vcs.enabled` only); the actual resolver routing is
 *    governed by per-component `component_sources.source`, with `defaultSource`
 *    as the fallback for unmigrated components. The two new diagnostic fields
 *    were added in the same PR as this check.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotPreconditionTest : CompatibilityTestBase() {

    @Test
    fun `record environment warnings — snapshot revision`() {
        val endpoint = "GET /rest/api/2/components-registry/service/status"
        val baselineResp = baselineRaw.get("/rest/api/2/components-registry/service/status")
        val candidateResp = candidateRaw.get("/rest/api/2/components-registry/service/status")

        val mapper = jacksonObjectMapper()
        val baseline = runCatching { mapper.readValue(baselineResp.bodyBytes, ServiceStatusSnapshot::class.java) }.getOrNull()
        val candidate = runCatching { mapper.readValue(candidateResp.bodyBytes, ServiceStatusSnapshot::class.java) }.getOrNull()

        // Baseline component count: fetched once here and passed into the pure
        // [evaluateEnvironmentPreflight] for the threshold computation. ArrayNode
        // guard: a non-2xx response, an undecodable body, or a JSON ObjectNode
        // (typical for error responses like `{"errorMessage": "..."}`) all map to
        // -1L so the evaluator falls back to the "any non-zero count" threshold
        // instead of letting an error-shaped response masquerade as a small
        // component-count via JsonNode.size() on ObjectNode returning property count.
        val baselineComponentCount: Long = runCatching {
            val resp = baselineRaw.get("/rest/api/3/components")
            if (resp.status in 200..299) {
                val node = mapper.readTree(resp.bodyBytes)
                if (node is ArrayNode) node.size().toLong() else -1L
            } else {
                -1L
            }
        }.getOrDefault(-1L)

        val allowNonDbCandidate = (
            System.getProperty("compat.allow-non-db-candidate")
                ?: System.getenv("COMPAT_ALLOW_NON_DB_CANDIDATE")
        )?.equals("true", ignoreCase = true) == true

        val records = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = baselineResp.status,
                baselineSnapshot = baseline,
                candidateStatus = candidateResp.status,
                candidateSnapshot = candidate,
                baselineComponentCount = baselineComponentCount,
                allowNonDbCandidate = allowNonDbCandidate,
            ),
            ts = Instant.now().toString(),
            endpoint = endpoint,
        )
        records.forEach { DiffCollector.record(it) }

        ExecutionLogger.log(
            ExecutionEntry(
                endpoint = endpoint,
                pathParams = emptyMap(),
                baselineStatus = baselineResp.status,
                candidateStatus = candidateResp.status,
                baselineMs = baselineResp.durationMs,
                candidateMs = candidateResp.durationMs,
                layer = "precondition",
                diffCount = records.size,
            ),
        )
    }
}

/**
 * Minimal local mirror of ServiceStatusDTO. Internal so jackson-module-kotlin
 * can instantiate it via reflection.
 */
internal data class ServiceStatusSnapshot(
    val cacheUpdatedAt: String? = null,
    val serviceMode: String? = null,
    val versionControlRevision: String? = null,
    // Diagnostic fields added in the same PR as the CANDIDATE_NOT_DB_MODE check.
    // Old candidates that predate the addition return null for both; treated as
    // "diagnostic surface not exposed".
    val defaultSource: String? = null,
    val dbComponentCount: Long? = null,
)
