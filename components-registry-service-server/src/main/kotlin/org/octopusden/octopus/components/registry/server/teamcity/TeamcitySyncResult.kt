package org.octopusden.octopus.components.registry.server.teamcity

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Wire shape of the resync admin endpoint response. Field names match the
 * plan (snake_case via @JsonProperty so the Kotlin property names stay
 * idiomatic).
 *
 * - `scanned`     — number of components considered (= number of non-archived
 *                   components in the registry at the moment of the call).
 * - `updated`     — components where either id or url actually changed.
 * - `unchanged`   — components matched and already correct (no DB write).
 * - `skippedNoMatch`   — components with no TC project carrying the parameter.
 * - `skippedAmbiguous` — components matched by ≥2 TC projects (skipped by policy).
 * - `errors`      — component-level error messages (TC API failures, etc.).
 *                   A top-level TC API failure aborts the whole sync and is
 *                   surfaced via HTTP error, not via this list.
 */
data class TeamcitySyncResult(
    val scanned: Int,
    val updated: Int,
    val unchanged: Int,
    @JsonProperty("skipped_no_match") val skippedNoMatch: Int,
    @JsonProperty("skipped_ambiguous") val skippedAmbiguous: Int,
    val errors: List<String>,
)
