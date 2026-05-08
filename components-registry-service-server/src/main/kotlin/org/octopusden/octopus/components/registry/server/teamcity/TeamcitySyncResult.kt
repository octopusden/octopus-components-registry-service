package org.octopusden.octopus.components.registry.server.teamcity

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Wire shape of one TC resync run's result. Embedded as `result` in
 * [TeamcitySyncJobState] once a job COMPLETES, and surfaced over HTTP via
 * `GET /admin/teamcity-project-ids/sync/job` inside [TeamcitySyncJobResponse].
 * Field names use snake_case via @JsonProperty so the Kotlin property names
 * stay idiomatic.
 *
 * - `scanned`     — number of components considered (= number of non-archived
 *                   components in the registry at the moment of the call).
 * - `updated`     — components where either id or url actually changed (counts
 *                   both single-candidate matches and ambiguous rows resolved
 *                   by the CDRelease tie-break).
 * - `unchanged`   — components matched and already correct (no DB write).
 * - `skippedNoMatch`   — components with no TC project carrying the parameter.
 * - `skippedAmbiguous` — components matched by ≥2 TC projects where the
 *                        CDRelease tie-break could not pick a winner (no
 *                        candidate owns a release build). Manual override is the
 *                        escape hatch.
 * - `ambiguousAutoResolved` — sub-counter of `updated`+`unchanged`: how many of
 *                              those rows came from a CDRelease tie-break on a
 *                              multi-candidate match. Lets ops see whether the
 *                              auto-resolve path is firing without grepping logs.
 * - `errors`      — component-level error messages caught during the post-fetch
 *                   `applyMatches` loop. A fetch-level TC API failure aborts the
 *                   whole sync and is surfaced via the job's `errorMessage`, not
 *                   via this list.
 */
data class TeamcitySyncResult(
    val scanned: Int,
    val updated: Int,
    val unchanged: Int,
    @JsonProperty("skipped_no_match") val skippedNoMatch: Int,
    @JsonProperty("skipped_ambiguous") val skippedAmbiguous: Int,
    @JsonProperty("ambiguous_auto_resolved") val ambiguousAutoResolved: Int,
    val errors: List<String>,
)
