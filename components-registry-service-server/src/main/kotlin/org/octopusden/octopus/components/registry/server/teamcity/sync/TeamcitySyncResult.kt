package org.octopusden.octopus.components.registry.server.teamcity.sync

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Wire shape of one TC resync run's result. Embedded as `result` in
 * [TeamcitySyncJobState] once a job COMPLETES, and surfaced over HTTP via
 * `GET /admin/teamcity-project-ids/sync/job` inside [TeamcitySyncJobResponse].
 * Field names use snake_case via @JsonProperty so the Kotlin property names
 * stay idiomatic.
 *
 * - `scanned` — non-archived components considered this run.
 * - `updated` — components whose linked project(s) actually changed (includes CDRelease tie-broken matches).
 * - `unchanged` — components matched and already correct (no DB write).
 * - `skippedNoMatch` — no TC project carries the parameter, or the only candidate(s) were dropped
 *   by the URL guard (blank/non-http(s) `webUrl`).
 * - `skippedAmbiguous` — matched by ≥2 TC projects with no CDRelease tie-break winner; needs a
 *   manual override.
 * - `ambiguousAutoResolved` — sub-counter of `updated`+`unchanged`: how many came from a CDRelease
 *   tie-break, so ops can see the auto-resolve path firing without grepping logs.
 * - `droppedLines` — individual `PROJECT_VERSION` lines dropped while their component still
 *   resolved via another line (ambiguous version group, or unusable `webUrl`). Kept separate from
 *   `skipped_ambiguous`/`skipped_no_match`, which only fire when a component resolves nothing, so a
 *   partially-linked component isn't invisible. Non-zero means some lines were silently dropped;
 *   the per-line reason is in the WARN logs.
 * - `errors` — per-component errors from the `applyMatches` loop. A fetch-level TC API failure
 *   instead aborts the whole sync and surfaces via the job's `errorMessage`.
 */
data class TeamcitySyncResult(
    val scanned: Int,
    val updated: Int,
    val unchanged: Int,
    @JsonProperty("skipped_no_match") val skippedNoMatch: Int,
    @JsonProperty("skipped_ambiguous") val skippedAmbiguous: Int,
    @JsonProperty("ambiguous_auto_resolved") val ambiguousAutoResolved: Int,
    @JsonProperty("dropped_lines") val droppedLines: Int,
    val errors: List<String>,
)
