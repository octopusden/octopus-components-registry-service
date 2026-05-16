package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory

/**
 * Stable pre-sort for raw-layer JSON arrays whose underlying server type is `Set<T>`.
 *
 * Why this exists: `JsonShape.diff` walks two JSON arrays positionally
 * (`baseline[i]` vs `candidate[i]`). For endpoints whose server-side type is a
 * `Set` (no contractual wire-order), the same logical payload can arrive in
 * different element orders on the two stands, producing thousands of spurious
 * STRUCTURAL_DIFF / ARRAY_SIZE_MISMATCH / TYPE_MISMATCH records that drown the
 * real signal. The typed layer already side-steps this by sorting DTOs before
 * recursive comparison (see e.g. `CommonControllerV2CompatTest sliceCollection`
 * and `ComponentsListCompatTest sorted-by-id`). This object brings the raw
 * layer in line — narrowly, opt-in per known offender, no implicit
 * auto-detection.
 */
object RawArraySorters {
    /**
     * U+0000 (NUL) as composite-key field separator. NUL cannot legally appear in
     * a component id or in a Maven version-range expression, so the resulting
     * key is unambiguous regardless of how those grammars evolve. Printable
     * separators each have at least one corner case (e.g. `[1.0, 1.1)` contains
     * a space; commas appear in artifact-list strings elsewhere) — NUL sidesteps
     * the question entirely.
     */
    private const val KEY_SEP = "\u0000"

    /**
     * Sort-key extractor per registered endpoint key. The endpoint string is the
     * same value the compat tests pass to `compareRaw(endpoint, …)`. Keep this
     * list minimal: only endpoints we have confirmed are Set-shape on the wire
     * and have been observed producing positional false-positives.
     */
    /**
     * Composite-key extractor for `Set<JiraComponentVersionRangeDTO>` — shared
     * between the global and per-project endpoints so the registry stays DRY
     * if the shape changes.
     */
    private val jiraComponentVersionRangeKey: (JsonNode) -> String = { node ->
        // Wire shape (per JiraComponentVersionRangeDTO):
        //   { "componentName": "...", "versionRange": "...", "component": { ... }, ... }
        // `componentName` lives at the TOP LEVEL, NOT under `component` (which is a
        // JiraComponentDTO with projectKey / displayName / componentInfo — no `id`).
        val componentName = node.path("componentName").asText("")
        val versionRange = node.path("versionRange").asText("")
        componentName + KEY_SEP + versionRange
    }

    private val sorters: Map<String, (JsonNode) -> String> =
        mapOf(
            // `/jira-component-version-ranges` (global) — Set<JiraComponentVersionRangeDTO>.
            // Stable key = componentName + NUL + versionRange — covers both kinds of
            // duplication (same component, different ranges; same range, different components).
            "GET /rest/api/2/common/jira-component-version-ranges" to jiraComponentVersionRangeKey,
            // `/projects/{projectKey}/jira-component-version-ranges` — same DTO type,
            // same Set semantics, just filtered by projectKey on the server. Used by
            // ProjectControllerV2CompatTest; without registration the per-project run
            // would re-introduce the same positional false-positives.
            "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges" to jiraComponentVersionRangeKey,
            // `/v3/components` returns a list of `{component, variants}` records — the server
            // contract is one entry per `component.id`. Sort by that id. `Set` guarantees
            // uniqueness but NOT a deterministic iteration order for equal sort keys; if
            // duplicate component ids ever reach this sorter, stable sorting would preserve
            // each stand's potentially-different input order, leaving residual positional
            // drift. Acceptable today because the server-side contract makes duplicates
            // a contract violation, not because of any Set-iteration guarantee.
            "GET /rest/api/3/components" to { node ->
                node.path("component").path("id").asText("")
            },
        )

    /**
     * Return a stable-sorted copy of [root] when [endpoint] is registered AND
     * [root] is an `ArrayNode`. Otherwise return [root] unchanged (identity).
     *
     * **Caller contract:** the returned node must be treated as read-only.
     * On the identity-pass-through path (unregistered endpoint or non-array
     * root) the return value aliases the input — mutating it would change the
     * caller's input as a side effect. On the sorted-copy path the return value
     * is a fresh `ArrayNode` whose element references are shared with the input,
     * so mutating elements is similarly visible from outside. `compareRaw` only
     * reads, so the alias is safe for the current call site; future callers
     * adding mutation must take a defensive copy themselves.
     *
     * The unit test `unregisteredEndpoint_passthrough` pins the identity contract.
     */
    fun stableSorted(endpoint: String, root: JsonNode?): JsonNode? {
        if (root !is ArrayNode) return root
        val keyOf = sorters[endpoint] ?: return root
        val sorted = root.toList().sortedBy(keyOf)
        // Preserve the original sort order ("stable") within equal keys: Kotlin's
        // `sortedBy` delegates to a stable JDK sort. `arrayNode(int)` is an
        // ArrayList capacity hint, not a fixed-size initialisation — we still
        // populate via `add`.
        val out = JsonNodeFactory.instance.arrayNode(sorted.size)
        sorted.forEach { out.add(it) }
        return out
    }
}
