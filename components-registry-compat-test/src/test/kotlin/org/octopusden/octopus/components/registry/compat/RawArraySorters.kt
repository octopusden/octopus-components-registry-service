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
     * Sort-key extractor per registered endpoint key. The endpoint string is the
     * same value the compat tests pass to `compareRaw(endpoint, …)`. Keep this
     * list minimal: only endpoints we have confirmed are Set-shape on the wire
     * and have been observed producing positional false-positives.
     */
    private val sorters: Map<String, (JsonNode) -> String> =
        mapOf(
            // `/jira-component-version-ranges` returns Set<JiraComponentVersionRangeDTO>.
            // Stable key = component id + version range — covers both kinds of
            // duplication (same component, different ranges; same range, different components).
            "GET /rest/api/2/common/jira-component-version-ranges" to { node ->
                val componentId = node.path("component").path("id").asText("")
                val versionRange = node.path("versionRange").asText("")
                "$componentId|$versionRange"
            },
            // `/v3/components` returns a list of `{component, variants}` records with one
            // entry per component. Sort by component id.
            "GET /rest/api/3/components" to { node ->
                node.path("component").path("id").asText("")
            },
        )

    /**
     * Return a stable-sorted copy of [root] when [endpoint] is registered AND
     * [root] is an `ArrayNode`. Otherwise return [root] unchanged (identity).
     *
     * The identity-return guarantee is load-bearing: callers can wrap every
     * raw-layer compare with this, and unregistered endpoints get exact pass-through
     * with no allocation. The unit test `unregisteredEndpoint_passthrough` pins
     * the contract.
     */
    fun stableSorted(endpoint: String, root: JsonNode?): JsonNode? {
        if (root !is ArrayNode) return root
        val keyOf = sorters[endpoint] ?: return root
        val sorted = root.toList().sortedBy(keyOf)
        // Preserve the original sort order ("stable") within equal keys by using sortedBy,
        // which delegates to a stable sort on the JDK side.
        val out = JsonNodeFactory.instance.arrayNode(sorted.size)
        sorted.forEach { out.add(it) }
        return out
    }
}
