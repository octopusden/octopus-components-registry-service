package org.octopusden.octopus.components.registry.compat

/**
 * Anti-flake policy for replay suites: a status pair where exactly ONE side
 * answered 5xx (and the other answered normally) is a candidate for ONE
 * re-fetch before being recorded as a diff.
 *
 * Rationale (2026-06-07 oracle run): under a heavily loaded agent the
 * candidate's Hikari pool starved and 5 trace tuples recorded 200→500 —
 * none reproducible against the same stands seconds later. A transient 5xx
 * is load noise, not a contract divergence; recording it poisons the frozen
 * oracle (false FIXED on the next run) and the diff-of-diffs NEW gate
 * (false rejections from unrelated transients).
 *
 * A DETERMINISTIC 5xx (a real candidate bug) reproduces on the immediate
 * retry and is still recorded — the policy only absorbs one-shot flakes.
 * Equal statuses (including both-5xx) are never retried: there is no status
 * diff to absorb, and a both-sides outage must surface, not loop.
 */
object TransientRetry {
    fun shouldRetry(
        baselineStatus: Int,
        candidateStatus: Int,
    ): Boolean {
        if (baselineStatus == candidateStatus) return false
        val baseline5xx = baselineStatus in 500..599
        val candidate5xx = candidateStatus in 500..599
        return baseline5xx != candidate5xx
    }
}
