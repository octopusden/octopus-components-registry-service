package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode

/**
 * Explains an `ARRAY_SIZE_MISMATCH` by naming the elements that differ.
 *
 * Without this, a size mismatch is opaque: the raw `DiffRecord` carries only the two counts
 * (`1372` vs `1374`), and the typed record — which does hold both collections — is truncated at
 * the collector's message cap, so the contents cannot be recovered from the artifacts either.
 * That makes it impossible to tell a recovered element from a wrongly-added one, which is exactly
 * the judgement a size mismatch demands before anything is suppressed.
 *
 * Elements are keyed by their identifying fields and compared as a **multiset**, so a duplicate
 * appearing on one side is reported rather than cancelling out against a distinct element.
 */
object ArraySizeDiagnostic {
    /** Fields tried, in order, to build a stable element key. */
    private val KEY_FIELDS = listOf("componentName", "versionRange", "id", "name", "projectKey")

    private const val MAX_LISTED = 15

    private fun keyOf(node: JsonNode): String {
        val parts =
            KEY_FIELDS.mapNotNull { f ->
                node.get(f)?.takeIf { !it.isNull }?.let { "$f=${it.asText()}" }
            }
        // No identifying field — fall back to the whole element so nothing silently collapses.
        return if (parts.isEmpty()) node.toString() else parts.joinToString(", ")
    }

    private fun isArray(node: JsonNode?): Boolean = node != null && node.isArray

    private fun multiset(node: JsonNode?): Map<String, Int> {
        if (node == null || !node.isArray) return emptyMap()
        val counts = LinkedHashMap<String, Int>()
        for (e in node) counts[keyOf(e)] = (counts[keyOf(e)] ?: 0) + 1
        return counts
    }

    /**
     * Returns a human-readable symmetric difference, or `null` when both sides are not arrays
     * (nothing useful to say) or when the multisets are identical — a size mismatch with equal
     * keyed multisets means the difference is inside the elements, not in their membership, and
     * that is itself worth stating.
     */
    fun describe(
        baseline: JsonNode?,
        candidate: JsonNode?,
    ): String? {
        if (!isArray(baseline) || !isArray(candidate)) return null

        val b = multiset(baseline)
        val c = multiset(candidate)

        val onlyCandidate = c.mapNotNull { (k, n) -> (n - (b[k] ?: 0)).takeIf { it > 0 }?.let { k to it } }
        val onlyBaseline = b.mapNotNull { (k, n) -> (n - (c[k] ?: 0)).takeIf { it > 0 }?.let { k to it } }

        if (onlyCandidate.isEmpty() && onlyBaseline.isEmpty()) {
            return " | membership identical by key — the size difference is NOT explained by element identity; " +
                "compare element contents"
        }

        // ONLY IN CANDIDATE = elements the candidate gained; ONLY IN BASELINE = elements it lost.
        // A loss is always a regression; a gain needs its provenance established before it is accepted.
        return render("ONLY IN CANDIDATE", onlyCandidate) + render("ONLY IN BASELINE", onlyBaseline)
    }

    private fun render(
        label: String,
        rows: List<Pair<String, Int>>,
    ): String {
        if (rows.isEmpty()) return ""
        val shown = rows.take(MAX_LISTED).joinToString("; ") { (k, n) -> if (n > 1) "$k ×$n" else k }
        val more = if (rows.size > MAX_LISTED) " (+${rows.size - MAX_LISTED} more)" else ""
        return " | $label [${rows.sumOf { it.second }}]: $shown$more"
    }
}
