package org.octopusden.octopus.components.registry.compat

/**
 * Pure inputs for [evaluateEnvironmentPreflight] — separates the impure HTTP/parse
 * stage from the classifier logic so the latter can be unit-tested with synthetic
 * fixtures (per the stricter compat-test-infra review protocol, every new diff-
 * classification branch needs a RED-then-GREEN demo on a pair of fixtures).
 */
internal data class EnvironmentPreflightInputs(
    val baselineStatus: Int,
    val baselineSnapshot: ServiceStatusSnapshot?,
    val candidateStatus: Int,
    val candidateSnapshot: ServiceStatusSnapshot?,
    /** Count of unique components in baseline's `/rest/api/3/components` listing.
     *  `-1L` signals discovery failed (non-2xx, undecodable, or response was not a
     *  JSON array — see ArrayNode guard at the caller); evaluator treats `-1L` as
     *  "no useful threshold available, fall back to >0". */
    val baselineComponentCount: Long,
)

/**
 * Evaluate the environment preflight: produce zero or more [DiffRecord]s with env
 * categories ([DiffClassifier.SNAPSHOT_MISMATCH], [DiffClassifier.CANDIDATE_NOT_DB_MODE]).
 *
 * Pure function — no HTTP, no time/date sources, no globals. The two impure inputs
 * are passed through:
 *
 * @param ts      timestamp string to stamp on every produced [DiffRecord]; caller
 *                  supplies `Instant.now().toString()` in production, fixed value in tests.
 * @param endpoint endpoint string used as the [DiffRecord.endpoint] field; defaults to
 *                  the `/service/status` GET path that the live test calls.
 */
internal fun evaluateEnvironmentPreflight(
    inputs: EnvironmentPreflightInputs,
    ts: String,
    endpoint: String = "GET /rest/api/2/components-registry/service/status",
): List<DiffRecord> {
    val records = mutableListOf<DiffRecord>()

    val baselineOk = inputs.baselineStatus in 200..299 && inputs.baselineSnapshot?.versionControlRevision != null
    val candidateOk = inputs.candidateStatus in 200..299 && inputs.candidateSnapshot?.versionControlRevision != null

    if (!baselineOk || !candidateOk) {
        // Fail-causing precondition: if /service/status is unreachable, non-2xx, or
        // undecodable, the run would otherwise proceed against bogus stands and the
        // reporter would see a clean diff count purely because every endpoint
        // returned the same non-contract response. Record once and short-circuit
        // the remaining checks (without a valid snapshot they have no inputs).
        records += DiffRecord(
            ts = ts,
            endpoint = endpoint,
            pathParams = emptyMap(),
            category = DiffClassifier.SNAPSHOT_MISMATCH,
            layer = "raw",
            baselineValue = if (baselineOk) inputs.baselineSnapshot?.versionControlRevision else "status=${inputs.baselineStatus}",
            candidateValue = if (candidateOk) inputs.candidateSnapshot?.versionControlRevision else "status=${inputs.candidateStatus}",
            message = "/service/status unreachable or undecodable on " + listOfNotNull(
                "baseline".takeIf { !baselineOk },
                "candidate".takeIf { !candidateOk },
            ).joinToString(" and ") + " — precondition for any compat run cannot be evaluated",
        )
        return records
    }

    val b = inputs.baselineSnapshot!!
    val c = inputs.candidateSnapshot!!

    if (b.versionControlRevision != c.versionControlRevision) {
        records += DiffRecord(
            ts = ts,
            endpoint = endpoint,
            pathParams = emptyMap(),
            category = DiffClassifier.SNAPSHOT_MISMATCH,
            layer = "raw",
            baselineValue = b.versionControlRevision,
            candidateValue = c.versionControlRevision,
            message = "baseline and candidate are pointed at different VCS revisions; data drift cannot be distinguished from migration regressions until they are re-synced",
        )
    }

    // CANDIDATE_NOT_DB_MODE check: candidate must report defaultSource="db" AND
    // dbComponentCount >= 90% of the baseline component count to confirm the
    // schema-v2 DB resolver is actually serving requests for substantially all
    // components. Either condition false → V1 path is dormant or only partially
    // migrated, and the compat result is V1-vs-V1 drift.
    //
    // The 90% threshold (vs strict equality) is chosen because:
    // - `requireMigrationSucceeded` on the candidate aborts startup if any
    //   component failed to import, so legitimate partial-migrate states never
    //   reach the gate.
    // - The remaining 10% slack absorbs minor DSL drift between baseline and
    //   candidate (a component just added on one side) without spurious env-
    //   warnings — those would surface anyway as MISSING_COMPONENT diffs.
    //
    // Integer-arithmetic ceil for the threshold: `(n*9 + 9) / 10 == ceil(0.9*n)`
    // for any non-negative integer n. Verified exhaustively for n ∈ {1, 5, 10,
    // 11, 100, 948}.
    //
    // Backward compat: an old candidate (pre-this-PR) returns defaultSource /
    // dbComponentCount as null; null is treated as "diagnostic surface not
    // exposed" and the warning fires so the operator either upgrades or
    // verifies out-of-band. Likewise if baseline discovery failed
    // (baselineComponentCount == -1L) the threshold collapses to 1, which makes
    // the check effectively "candidate must have >0 db-routed components" —
    // weaker than the 90% target but a clear non-zero signal that DB-mode is
    // on. The companion SNAPSHOT_MISMATCH check above surfaces the broken
    // baseline so the operator sees the root cause.
    val threshold: Long = if (inputs.baselineComponentCount > 0L) {
        (inputs.baselineComponentCount * 9 + 9) / 10
    } else {
        1L
    }
    val candidateInDbMode =
        c.defaultSource == "db" && (c.dbComponentCount ?: 0L) >= threshold
    if (!candidateInDbMode) {
        records += DiffRecord(
            ts = ts,
            endpoint = endpoint,
            pathParams = emptyMap(),
            category = DiffClassifier.CANDIDATE_NOT_DB_MODE,
            layer = "raw",
            baselineValue = "baselineComponentCount=${inputs.baselineComponentCount}, threshold=$threshold",
            candidateValue = "defaultSource=${c.defaultSource ?: "<null — old candidate>"}, dbComponentCount=${c.dbComponentCount ?: "<null>"}",
            message = "candidate's /service/status indicates the schema-v2 DB resolver is dormant " +
                "or only partially active (needs defaultSource=\"db\" AND dbComponentCount >= " +
                "0.9 × baselineComponentCount). Any diffs reported by this compat run are V1-vs-V1 " +
                "drift, NOT real schema-v2-vs-V1 regressions. Fix the candidate config (set " +
                "components-registry.default-source=db, ensure auto-migrate ran with failed=0) " +
                "before treating the report as authoritative.",
        )
    }

    return records
}
