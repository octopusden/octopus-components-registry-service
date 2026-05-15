package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/**
 * Pre-flight environment check: read /service/status from both stands and record
 * the VCS-revision mismatch as an env-level diff so it appears at the top of
 * `summary.md` and explains downstream divergences.
 *
 * The test method itself ALWAYS passes — it only records [DiffRecord]s. The Gradle
 * `compatibilityReporter` task aggregates them; env categories (currently only
 * `SNAPSHOT_MISMATCH`) are surfaced separately and are NOT suppressible via
 * `known-deltas.json` (they signal that the comparison itself is unsound, not an
 * intentional v3 delta).
 *
 * Why a recorded diff and not a fail-fast assertion: per operator decision, we
 * want the full compat surface to run even when stands disagree on snapshot, so
 * all real divergences are visible in one report. The environment warning is
 * just *one more record* in summary.md, surfaced at the top.
 *
 * NOTE: a "candidate not in DB mode" precondition lived here historically. It was
 * removed because the public `ServiceStatusDTO.serviceMode` enum on this branch
 * is `{FS, VCS}` only — there is no `DB` value to compare against, so the check
 * unconditionally fired regardless of the candidate's actual read path. The
 * candidate's source mode (Git vs DB) is an internal selector via
 * `ComponentSourceRegistry` / profile settings and is not exposed via this DTO;
 * operators must verify it out-of-band.
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

        val ts = Instant.now().toString()

        // Fail-causing precondition: if /service/status itself is unreachable, returns
        // non-2xx, or yields a body we can't decode (HTML error page, wrong base URL,
        // auth failure), both snapshots come back null — without this record the run
        // would proceed against bogus stands and the reporter would see a clean diff
        // count purely because every endpoint returned the same non-contract response.
        val baselineOk = baselineResp.status in 200..299 && baseline?.versionControlRevision != null
        val candidateOk = candidateResp.status in 200..299 && candidate?.versionControlRevision != null
        if (!baselineOk || !candidateOk) {
            DiffCollector.record(
                DiffRecord(
                    ts = ts,
                    endpoint = endpoint,
                    pathParams = emptyMap(),
                    category = DiffClassifier.SNAPSHOT_MISMATCH,
                    layer = "raw",
                    baselineValue = if (baselineOk) baseline?.versionControlRevision else "status=${baselineResp.status}",
                    candidateValue = if (candidateOk) candidate?.versionControlRevision else "status=${candidateResp.status}",
                    message = "/service/status unreachable or undecodable on " + listOfNotNull(
                        "baseline".takeIf { !baselineOk },
                        "candidate".takeIf { !candidateOk },
                    ).joinToString(" and ") + " — precondition for any compat run cannot be evaluated",
                ),
            )
        } else if (baseline.versionControlRevision != candidate.versionControlRevision) {
            DiffCollector.record(
                DiffRecord(
                    ts = ts,
                    endpoint = endpoint,
                    pathParams = emptyMap(),
                    category = DiffClassifier.SNAPSHOT_MISMATCH,
                    layer = "raw",
                    baselineValue = baseline.versionControlRevision,
                    candidateValue = candidate.versionControlRevision,
                    message = "baseline and candidate are pointed at different VCS revisions; data drift cannot be distinguished from migration regressions until they are re-synced",
                ),
            )
        }

        ExecutionLogger.log(
            ExecutionEntry(
                endpoint = endpoint,
                pathParams = emptyMap(),
                baselineStatus = baselineResp.status,
                candidateStatus = candidateResp.status,
                baselineMs = baselineResp.durationMs,
                candidateMs = candidateResp.durationMs,
                layer = "precondition",
                diffCount = 0,
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
)
