package org.octopusden.octopus.components.registry.compat

/**
 * The known-delta matching of `compatMatchesDelta` in build.gradle, for unit tests that pin an
 * entry of known-deltas-*.json against records built by the real comparators: endpoint or
 * endpointPattern, category, and `find()` on jsonPathPattern / messagePattern, with
 * `{{pathParam:<name>}}` in messagePattern resolved as in `compatResolvePathParams`. Entries with
 * exact baselineValue / candidateValue / pathParams never match here; the tests use none.
 */
internal object KnownDeltas {
    private val PATH_PARAM = Regex("""\{\{pathParam:([^}]+)}}""")

    fun suppressed(
        deltas: List<Map<String, Any?>>,
        record: DiffRecord,
    ): Boolean =
        deltas.any { d ->
            (d["endpoint"] == null || d["endpoint"] == record.endpoint) &&
                (d["endpointPattern"] == null || Regex(d["endpointPattern"] as String).containsMatchIn(record.endpoint)) &&
                (d["endpoint"] != null || d["endpointPattern"] != null) &&
                d["category"] == record.category.name &&
                d["baselineValue"] == null &&
                d["candidateValue"] == null &&
                d["pathParams"] == null &&
                matchesIfSet(d["jsonPathPattern"] as String?, record.jsonPath) &&
                messageMatches(d["messagePattern"] as String?, record)
        }

    private fun matchesIfSet(
        pattern: String?,
        value: String?,
    ) = pattern == null || (value != null && Regex(pattern).containsMatchIn(value))

    private fun messageMatches(
        pattern: String?,
        record: DiffRecord,
    ): Boolean {
        if (pattern == null) return true
        val resolved = resolvePathParams(pattern, record.pathParams) ?: return false
        return matchesIfSet(resolved, record.message)
    }

    /** `{{pathParam:<name>}}` → that path parameter of the record, regex-quoted; null when the record has none. */
    private fun resolvePathParams(
        pattern: String,
        pathParams: Map<String, String>,
    ): String? {
        val resolved = StringBuilder()
        var last = 0
        for (m in PATH_PARAM.findAll(pattern)) {
            val value = pathParams[m.groupValues[1]] ?: return null
            resolved.append(pattern, last, m.range.first).append(Regex.escape(value))
            last = m.range.last + 1
        }
        return resolved.append(pattern, last, pattern.length).toString()
    }
}
