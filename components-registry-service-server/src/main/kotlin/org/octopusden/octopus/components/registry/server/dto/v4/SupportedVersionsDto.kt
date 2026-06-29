package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * The component's **supported versions** (coverage) — the first layer of the decoupled version
 * model (ADR-018), independent of per-attribute overrides. `resolve(v)` returns 404 outside it.
 *
 * - `all = true` ⇔ the component is defined for **all** versions (no bounded `RANGE_PRESENCE` rows);
 *   `ranges` is then empty.
 * - `all = false` ⇒ `supported = ∪ ranges`, the verbatim declared coverage ranges (a composite
 *   range stays a single string).
 *
 * `warnings` carries non-blocking advisories from a write (e.g. an override left outside supported).
 */
data class SupportedVersionsResponse(
    val all: Boolean,
    val ranges: List<String>,
    val warnings: List<String> = emptyList(),
)

/**
 * Declarative replacement of a component's supported versions. The portal computes the desired set
 * (extend / limit / split) client-side and PUTs the result; the server replaces the `RANGE_PRESENCE`
 * coverage rows to match and re-aligns existing per-attribute overrides to the new breakpoints
 * (write-time auto-split, ADR-018 (b)).
 *
 * - `all = true` (or both fields omitted / `ranges` empty) → supported = ALL (clears bounded coverage).
 * - otherwise → supported = `∪ ranges`. Each range must parse; composite ranges are accepted verbatim.
 */
data class SupportedVersionsRequest(
    val all: Boolean? = null,
    val ranges: List<String>? = null,
)
