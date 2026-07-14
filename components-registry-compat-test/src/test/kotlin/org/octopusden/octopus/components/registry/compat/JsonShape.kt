package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Structural JSON diff — keys, leaf types, collection sizes — independent of values.
 *
 * Used for "shape" classification before falling through to DTO-level value comparison.
 * Returned diffs feed into [DiffClassifier.STRUCTURAL_DIFF] / [DiffClassifier.NULL_VS_EMPTY] /
 * [DiffClassifier.COLLECTION_ORDER] decisions.
 */
object JsonShape {
    data class ShapeDiff(
        val path: String,
        val kind: Kind,
        val baseline: String?,
        val candidate: String?,
    ) {
        enum class Kind { KEY_MISSING_BASELINE, KEY_MISSING_CANDIDATE, TYPE_MISMATCH, ARRAY_SIZE_MISMATCH }
    }

    fun diff(
        baseline: JsonNode?,
        candidate: JsonNode?,
        path: String = "$",
    ): List<ShapeDiff> {
        if (baseline == null && candidate == null) return emptyList()
        if (baseline == null) return listOf(ShapeDiff(path, ShapeDiff.Kind.KEY_MISSING_BASELINE, null, candidate?.nodeType?.name))
        if (candidate == null) return listOf(ShapeDiff(path, ShapeDiff.Kind.KEY_MISSING_CANDIDATE, baseline.nodeType.name, null))
        if (baseline.nodeType != candidate.nodeType) {
            return listOf(ShapeDiff(path, ShapeDiff.Kind.TYPE_MISMATCH, baseline.nodeType.name, candidate.nodeType.name))
        }
        return when (baseline) {
            is ObjectNode -> diffObjects(baseline, candidate as ObjectNode, path)
            is ArrayNode -> diffArrays(baseline, candidate as ArrayNode, path)
            else -> emptyList() // leaf nodes — shape OK; values are checked at typed layer
        }
    }

    private fun diffObjects(
        a: ObjectNode,
        b: ObjectNode,
        path: String,
    ): List<ShapeDiff> {
        val out = mutableListOf<ShapeDiff>()
        val keys = (a.fieldNames().asSequence() + b.fieldNames().asSequence()).toSortedSet()
        for (k in keys) {
            val nextPath = path + segment(k)
            val av = a.get(k)
            val bv = b.get(k)
            out += diff(av, bv, nextPath)
        }
        return out
    }

    /**
     * JSON-Pointer-ish path segment. Simple identifiers use `.key`; anything containing
     * dots, brackets, parens, commas, or whitespace falls back to `["key"]` so the path
     * stays unambiguous for keys like `(,0),[0,)`.
     */
    private fun segment(key: String): String {
        val simple = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
        return if (simple.matches(key)) ".$key" else "[\"${key.replace("\"", "\\\"")}\"]"
    }

    private fun diffArrays(
        a: ArrayNode,
        b: ArrayNode,
        path: String,
    ): List<ShapeDiff> {
        if (a.size() != b.size()) {
            return listOf(ShapeDiff(path, ShapeDiff.Kind.ARRAY_SIZE_MISMATCH, a.size().toString(), b.size().toString()))
        }
        val out = mutableListOf<ShapeDiff>()
        for (i in 0 until a.size()) {
            out += diff(a[i], b[i], "$path[$i]")
        }
        return out
    }
}
