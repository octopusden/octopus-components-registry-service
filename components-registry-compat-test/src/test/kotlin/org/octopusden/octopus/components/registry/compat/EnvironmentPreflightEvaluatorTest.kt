package org.octopusden.octopus.components.registry.compat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * RED-then-GREEN unit coverage for [evaluateEnvironmentPreflight] — satisfies the
 * compat-test-infra review protocol requirement that "every new diff-classification
 * branch needs a RED-then-GREEN demo".
 *
 * Each test constructs a synthetic [EnvironmentPreflightInputs] and asserts the
 * exact list of [DiffRecord]s the evaluator should produce. A change to the
 * evaluator that silently swallows a classification (or fires one when it
 * shouldn't) breaks the corresponding test by going RED — exactly the regression
 * signal the protocol mandates.
 */
@Tag("unit")
class EnvironmentPreflightEvaluatorTest {

    private val ts = "2026-05-16T20:00:00Z"

    private fun ok(rev: String, defaultSource: String? = "db", dbComponentCount: Long? = 948L) =
        ServiceStatusSnapshot(
            cacheUpdatedAt = "2026-05-16T19:59:00Z",
            serviceMode = "VCS",
            versionControlRevision = rev,
            defaultSource = defaultSource,
            dbComponentCount = dbComponentCount,
        )

    @Test
    fun `happy path — both stands healthy, revs match, candidate in DB mode → no diffs`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A", defaultSource = null, dbComponentCount = null), // baseline = main code, fields not exposed
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = "db", dbComponentCount = 948L),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).isEmpty()
    }

    @Test
    fun `baseline unreachable — single SNAPSHOT_MISMATCH 'unreachable', subsequent checks skipped`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 503,
                baselineSnapshot = null,
                candidateStatus = 200,
                candidateSnapshot = ok("rev-X", defaultSource = "db", dbComponentCount = 948L),
                baselineComponentCount = -1L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        val r = out.single()
        assertThat(r.category).isEqualTo(DiffClassifier.SNAPSHOT_MISMATCH)
        assertThat(r.message).contains("unreachable", "baseline")
        // Crucially: the CANDIDATE_NOT_DB_MODE check does NOT fire on this path
        // (no valid snapshot pair → comparison is not meaningful).
    }

    @Test
    fun `candidate unreachable — single SNAPSHOT_MISMATCH, candidate side identified`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 502,
                candidateSnapshot = null,
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        assertThat(out.single().message).contains("unreachable", "candidate")
    }

    @Test
    fun `revisions differ — SNAPSHOT_MISMATCH 'differ' fires, DB-mode check ALSO runs and passes`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A", defaultSource = null, dbComponentCount = null),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-B", defaultSource = "db", dbComponentCount = 948L),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        assertThat(out.single().category).isEqualTo(DiffClassifier.SNAPSHOT_MISMATCH)
        assertThat(out.single().message).contains("different VCS revisions")
    }

    @Test
    fun `candidate defaultSource is git — CANDIDATE_NOT_DB_MODE fires`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A", defaultSource = null, dbComponentCount = null),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = "git", dbComponentCount = 948L),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        assertThat(out.single().category).isEqualTo(DiffClassifier.CANDIDATE_NOT_DB_MODE)
        assertThat(out.single().candidateValue).contains("defaultSource=git")
    }

    @Test
    fun `allowNonDbCandidate=true suppresses CANDIDATE_NOT_DB_MODE but not SNAPSHOT_MISMATCH`() {
        // Same misconfiguration as the test above (candidate defaultSource=git), PLUS a
        // revision drift. The documented escape hatch must suppress the DB-mode warning
        // (operator is intentionally running parity-debug) but the unrelated
        // SNAPSHOT_MISMATCH must still surface — different invariant.
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-B", defaultSource = "git", dbComponentCount = 948L),
                baselineComponentCount = 948L,
                allowNonDbCandidate = true,
            ),
            ts = ts,
        )
        assertThat(out.map { it.category })
            .containsExactly(DiffClassifier.SNAPSHOT_MISMATCH)
    }

    @Test
    fun `candidate dbComponentCount below 0_9x threshold — CANDIDATE_NOT_DB_MODE fires`() {
        // baseline=948 → threshold=ceil(0.9*948)=854; candidate=853 falls just below
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = "db", dbComponentCount = 853L),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        assertThat(out.single().category).isEqualTo(DiffClassifier.CANDIDATE_NOT_DB_MODE)
        assertThat(out.single().baselineValue).contains("threshold=854")
        assertThat(out.single().candidateValue).contains("dbComponentCount=853")
    }

    @Test
    fun `candidate dbComponentCount exactly at threshold — passes (boundary)`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = "db", dbComponentCount = 854L),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).isEmpty()
    }

    @Test
    fun `candidate diagnostic fields null — CANDIDATE_NOT_DB_MODE fires with 'old candidate' hint`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = null, dbComponentCount = null),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        assertThat(out.single().category).isEqualTo(DiffClassifier.CANDIDATE_NOT_DB_MODE)
        assertThat(out.single().candidateValue).contains("<null — old candidate>")
    }

    @Test
    fun `baseline discovery failed (count -1) — threshold collapses to 1, candidate with count=0 still fails`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = "db", dbComponentCount = 0L),
                baselineComponentCount = -1L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(1)
        assertThat(out.single().category).isEqualTo(DiffClassifier.CANDIDATE_NOT_DB_MODE)
    }

    @Test
    fun `baseline discovery failed but candidate has count=1 — passes weak fallback`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-A", defaultSource = "db", dbComponentCount = 1L),
                baselineComponentCount = -1L,
            ),
            ts = ts,
        )
        assertThat(out).isEmpty()
    }

    @Test
    fun `revisions differ AND db-mode wrong — both records fire (independent)`() {
        val out = evaluateEnvironmentPreflight(
            EnvironmentPreflightInputs(
                baselineStatus = 200,
                baselineSnapshot = ok("rev-A"),
                candidateStatus = 200,
                candidateSnapshot = ok("rev-B", defaultSource = "git", dbComponentCount = 0L),
                baselineComponentCount = 948L,
            ),
            ts = ts,
        )
        assertThat(out).hasSize(2)
        assertThat(out.map { it.category })
            .containsExactly(DiffClassifier.SNAPSHOT_MISMATCH, DiffClassifier.CANDIDATE_NOT_DB_MODE)
    }

    @Test
    fun `integer ceil math is exact across boundary values`() {
        // Verifies the (n*9 + 9) / 10 = ceil(0.9 * n) identity for documented test points.
        // Build via the public API rather than reaching into private helpers: a candidate
        // with dbComponentCount = threshold-1 must fire; dbComponentCount = threshold passes.
        fun threshold(baselineCount: Long): Long {
            val out = evaluateEnvironmentPreflight(
                EnvironmentPreflightInputs(
                    baselineStatus = 200,
                    baselineSnapshot = ok("rev-A"),
                    candidateStatus = 200,
                    candidateSnapshot = ok("rev-A", defaultSource = "db", dbComponentCount = 0L),
                    baselineComponentCount = baselineCount,
                ),
                ts = ts,
            )
            // The CANDIDATE_NOT_DB_MODE record echoes "threshold=N" in baselineValue.
            val rec = out.single { it.category == DiffClassifier.CANDIDATE_NOT_DB_MODE }
            val match = Regex("threshold=(\\d+)").find(rec.baselineValue ?: "")
            return match!!.groupValues[1].toLong()
        }
        assertThat(threshold(1L)).isEqualTo(1L)
        assertThat(threshold(5L)).isEqualTo(5L) // ceil(0.9*5) = ceil(4.5) = 5
        assertThat(threshold(10L)).isEqualTo(9L) // ceil(0.9*10) = 9
        assertThat(threshold(11L)).isEqualTo(10L) // ceil(0.9*11) = ceil(9.9) = 10
        assertThat(threshold(100L)).isEqualTo(90L)
        assertThat(threshold(948L)).isEqualTo(854L) // ceil(0.9*948) = ceil(853.2) = 854
    }
}
