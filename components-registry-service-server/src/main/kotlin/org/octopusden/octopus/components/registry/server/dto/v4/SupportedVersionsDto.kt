package org.octopusden.octopus.components.registry.server.dto.v4

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern

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
    // Optional change metadata recorded on the audit row (not on the component) — mirrors
    // ComponentCreateRequest / ComponentUpdateRequest so a coverage change carries its "why".
    // A blank/whitespace key is accepted as "no key" (normalized to null); a non-blank key must
    // match the Jira key format. See JIRA_TASK_KEY_PATTERN.
    @field:Pattern(
        regexp = JIRA_TASK_KEY_PATTERN,
        message = "must be a Jira task key like ABC-123",
    )
    @field:Schema(
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        description = "Optional Jira task key motivating the change (e.g. ABC-123); recorded on the audit row.",
    )
    val jiraTaskKey: String? = null,
    @field:Schema(
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        description = "Optional free-text comment describing the change; recorded on the audit row.",
    )
    val changeComment: String? = null,
)
