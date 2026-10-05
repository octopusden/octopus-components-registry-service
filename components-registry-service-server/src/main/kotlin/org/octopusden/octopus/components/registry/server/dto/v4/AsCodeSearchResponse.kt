package org.octopusden.octopus.components.registry.server.dto.v4

/**
 * Result of `GET /rest/api/4/components/as-code/search` (SYS-098): a grep over every
 * component's FULL as-code text, grouped by component.
 */
data class AsCodeSearchResponse(
    /** The trimmed query that was matched. */
    val query: String,
    /** `true` when [query] was matched as a case-insensitive regular expression. */
    val regex: Boolean,
    /** Number of components with at least one matching line, BEFORE [results] is cut at `limit`. */
    val totalComponents: Int,
    /** `true` when [totalComponents] exceeds the number of entries in [results]. */
    val truncated: Boolean,
    /** Matching components, sorted by component key. */
    val results: List<AsCodeSearchHit>,
)

data class AsCodeSearchHit(
    val componentKey: String,
    val archived: Boolean,
    /** Total matching lines in this component, even when [matches] is capped. */
    val matchCount: Int,
    /** The first matching lines (capped at `maxMatchesPerComponent`), in document order. */
    val matches: List<AsCodeSearchLine>,
)

data class AsCodeSearchLine(
    /** 1-based line number in the FULL as-code view (`GET /components/{id}/as-code`). */
    val line: Int,
    /** The matching line, leading indentation removed. */
    val text: String,
    /**
     * Enclosing block headers as rendered, outermost first — e.g. `["\"my-component\"", "\"[1.5,)\"", "jira"]`
     * (a key that is not a plain identifier is double-quoted) — so a hit inside a version-range block is
     * identifiable without opening the component.
     */
    val path: List<String>,
)
