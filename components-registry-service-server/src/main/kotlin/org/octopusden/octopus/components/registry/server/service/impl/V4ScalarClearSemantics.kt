package org.octopusden.octopus.components.registry.server.service.impl

/**
 * CRS-A — v4 write-side clear semantics for aspect string scalars
 * (`build` / `escrow` / `jira`) and the top-level `vcsExternalRegistry`.
 *
 * Applied AFTER the caller's `?.let`, which already handles the null/absent
 * (no-op) case. What remains is a two-way decision on a present value:
 *  - `""` or whitespace-only (blank after trim) → clear the column (persist NULL),
 *  - non-blank → set verbatim (no trimming).
 *
 * `""` was never emitted by any v4 client before this rule, so promoting it
 * from "store the empty string" to "clear" is safe. Storing NULL (not "") also
 * keeps the resolver's `?:` format fallbacks working — an empty string is
 * non-null and would defeat them (see ClearedFormatFallbackResolverTest). The
 * tri-state is documented on the affected OpenAPI field descriptions.
 */
internal fun clearBlankScalar(incoming: String): String? = incoming.ifBlank { null }
