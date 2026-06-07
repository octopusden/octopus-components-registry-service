package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

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
        // (componentName, versionRange) is NOT unique within a project's response
        // (observed in production: several entries share it but differ in distribution /
        // vcsSettings / component.displayName). A colliding key lets the stable sort keep
        // each stand's arbitrary Set-iteration order → positional misalignment → false
        // STRUCTURAL_DIFFs. Append a canonical (recursively field-name-sorted) KEY STRING of
        // the WHOLE element as a tie-breaker — collision-free and deterministic on both
        // stands (it is a stable sort key, NOT a JSON document). It never masks a real diff:
        // two elements differing in any field get different keys, so a genuine divergence
        // still surfaces as that element, not as positional noise.
        componentName + KEY_SEP + versionRange + KEY_SEP + canonicalSortKey(node)
    }

    /**
     * A transform applied to the JSON root when an endpoint is registered. Two
     * shapes are supported today via the helpers below:
     *
     *  - `topLevelArraySort(keyOf)` — the wire root IS the array (v3 `/components`,
     *    `/jira-component-version-ranges`).
     *  - `nestedArraySort(field, keyOf)` — the wire root is an object `{ <field>: [...] }`
     *    (v1 / v2 `/components` wrap the array under `"components"`).
     *
     * Anything more elaborate (multiple nested arrays per response, etc.) should
     * land as a new helper rather than inline lambdas, so the registry below
     * stays readable.
     */
    private fun interface Transform {
        fun apply(root: JsonNode): JsonNode
    }

    private val transforms: Map<String, Transform> =
        mapOf(
            // `/jira-component-version-ranges` (global) — Set<JiraComponentVersionRangeDTO>.
            // Stable key = componentName + NUL + versionRange — covers both kinds of
            // duplication (same component, different ranges; same range, different components).
            "GET /rest/api/2/common/jira-component-version-ranges" to topLevelArraySort(jiraComponentVersionRangeKey),
            // `/projects/{projectKey}/jira-component-version-ranges` — same DTO type,
            // same Set semantics, just filtered by projectKey on the server. Used by
            // ProjectControllerV2CompatTest; without registration the per-project run
            // would re-introduce the same positional false-positives.
            "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges" to topLevelArraySort(jiraComponentVersionRangeKey),
            // `/v3/components` returns a list of `{component, variants}` records — the server
            // contract is one entry per `component.id`. Sort by that id. `Set` guarantees
            // uniqueness but NOT a deterministic iteration order for equal sort keys; if
            // duplicate component ids ever reach this sorter, stable sorting would preserve
            // each stand's potentially-different input order, leaving residual positional
            // drift. Acceptable today because the server-side contract makes duplicates
            // a contract violation, not because of any Set-iteration guarantee.
            "GET /rest/api/3/components" to topLevelArraySort { node ->
                node.path("component").path("id").asText("")
            },
            // `/v1/components` and `/v2/components` wrap the array under `"components"`.
            // The server's source-of-truth for V1 is `EscrowConfiguration.escrowModules`
            // — a `java.util.HashMap` (component-resolver-core/.../EscrowConfiguration.groovy:
            // `Map<String, EscrowModule> escrowModules = new HashMap<>()`). HashMap
            // iteration order depends on key hashes and bucket-array sizing, so two JVM
            // instances of the same V1 codebase can — and do — return the 948 components
            // in different orders. Until the V1 contract is upgraded to a deterministic
            // map (which would also be a backward-compat extension affecting clients),
            // this endpoint is Set-shape and must be pre-sorted in the harness.
            "GET /rest/api/1/components" to nestedArraySort("components") { node ->
                node.path("id").asText("")
            },
            "GET /rest/api/2/components" to nestedArraySort("components") { node ->
                node.path("id").asText("")
            },
        )

    /**
     * Return a copy of [root] with any registered Set-shape array stable-sorted.
     * For unregistered endpoints, non-array roots (under the top-level helper),
     * or roots without the expected nested field (under the nested helper),
     * the input is returned unchanged.
     *
     * **Caller contract:** the returned node must be treated as read-only.
     * On the identity-pass-through path the return value aliases the input.
     * On the transformed path the returned root is a fresh node, but its
     * element references may still alias the input — mutating elements is
     * visible from outside. `compareRaw` only reads, so the alias is safe for
     * the current call site; future callers adding mutation must take a
     * defensive copy themselves.
     *
     * The unit tests `unregisteredEndpoint_passthrough` and `nonArrayRoot_passthrough`
     * pin the identity contract.
     */
    fun stableSorted(endpoint: String, root: JsonNode?): JsonNode? {
        if (root == null) return null
        // Fold the RAW trace-replay spelling onto the templated registry key —
        // without this the per-project endpoints fell through the exact-match
        // map and trace replay compared Set-shape arrays positionally.
        val transform = transforms[CompatEntityContext.canonicalEndpoint(endpoint)] ?: return root
        return transform.apply(root)
    }

    private fun topLevelArraySort(keyOf: (JsonNode) -> String): Transform =
        Transform { root ->
            if (root !is ArrayNode) root else sortedCopy(root, keyOf)
        }

    private fun nestedArraySort(field: String, keyOf: (JsonNode) -> String): Transform =
        Transform { root ->
            if (root !is ObjectNode) return@Transform root
            val inner = root.get(field) as? ArrayNode ?: return@Transform root
            // Shallow copy: same field set, but the target field is replaced with
            // the sorted array. Avoids deep-copying every element — expensive on
            // 948-entry responses and unnecessary since downstream only reads.
            val out = JsonNodeFactory.instance.objectNode()
            out.setAll<ObjectNode>(root)
            out.set<JsonNode>(field, sortedCopy(inner, keyOf))
            out
        }

    private fun sortedCopy(arr: ArrayNode, keyOf: (JsonNode) -> String): ArrayNode {
        // Preserve the original sort order ("stable") within equal keys: Kotlin's
        // `sortedBy` delegates to a stable JDK sort. `arrayNode(int)` is an
        // ArrayList capacity hint, not a fixed-size initialisation — we still
        // populate via `add`.
        val sorted = arr.toList().sortedBy(keyOf)
        val out = JsonNodeFactory.instance.arrayNode(sorted.size)
        sorted.forEach { out.add(it) }
        return out
    }

    /**
     * Deterministic, field-order-independent KEY STRING for [node]: object field names are
     * sorted recursively (and quoted), so two structurally-equal elements that differ only
     * in JSON field ORDER (Jackson preserves wire order, which can differ between the two
     * stands) produce the SAME string. This is a stable SORT KEY, not a JSON document —
     * field names are quoted so the concatenation is unambiguous. Used only as a Set-sort
     * tie-breaker; inner-array order is left as-is, since a size/content difference inside
     * an element is a real per-element difference and SHOULD change the key.
     */
    private fun canonicalSortKey(node: JsonNode): String {
        val sb = StringBuilder()
        appendCanonical(node, sb)
        return sb.toString()
    }

    private fun appendCanonical(node: JsonNode, sb: StringBuilder) {
        when {
            node.isObject -> {
                sb.append('{')
                var first = true
                node.fieldNames().asSequence().sorted().forEach { name ->
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(name).append('"').append(':')
                    appendCanonical(node.get(name), sb)
                }
                sb.append('}')
            }
            node.isArray -> {
                sb.append('[')
                var first = true
                node.forEach { child ->
                    if (!first) sb.append(',')
                    first = false
                    appendCanonical(child, sb)
                }
                sb.append(']')
            }
            else -> sb.append(node.toString())
        }
    }
}
