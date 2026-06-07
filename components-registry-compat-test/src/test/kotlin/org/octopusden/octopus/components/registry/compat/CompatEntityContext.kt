package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode

/**
 * Resolves human-readable entity keys for compat diff records.
 *
 * Raw-layer STRUCTURAL_DIFF messages use positional JSON paths (`$[3].field`).
 * After [RawArraySorters] alignment those indices are stable but opaque — this
 * helper maps them back to `(componentName, versionRange)` for cluster triage.
 */
object CompatEntityContext {
    private val arrayIndex = Regex("""\$\[(\d+)\]""")

    private val jiraRangesEndpoints =
        setOf(
            "GET /rest/api/2/common/jira-component-version-ranges",
            "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges",
        )

    fun resolveEntityKey(
        endpoint: String,
        jsonPath: String,
        pathParams: Map<String, String>,
        baselineJson: JsonNode?,
        candidateJson: JsonNode?,
    ): String? {
        if (endpoint in jiraRangesEndpoints) {
            val index = arrayIndex.find(jsonPath)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val element =
                elementAt(RawArraySorters.stableSorted(endpoint, baselineJson), index)
                    ?: elementAt(RawArraySorters.stableSorted(endpoint, candidateJson), index)
                    ?: return null
            val componentName = element.path("componentName").asText("").ifBlank { "?" }
            val versionRange = element.path("versionRange").asText("").ifBlank { "?" }
            val project = pathParams["projectKey"]?.takeIf { it.isNotBlank() }
            return if (project == null) {
                "$componentName @ $versionRange"
            } else {
                "$project / $componentName @ $versionRange"
            }
        }
        val component = pathParams["component"]?.takeIf { it.isNotBlank() }
        val version = pathParams["version"]?.takeIf { it.isNotBlank() }
        if (component != null && version != null) {
            return "$component @ $version"
        }
        if (component != null) {
            return component
        }
        return null
    }

    /** Collapse `$[3]` → `$[N]` so cluster digest groups by field, not wire index. */
    fun normalizeFieldPath(jsonPath: String): String =
        jsonPath.replace(arrayIndex, Regex.escapeReplacement("$" + "[N]"))

    private fun elementAt(root: JsonNode?, index: Int): JsonNode? {
        if (root == null || !root.isArray || index < 0 || index >= root.size()) {
            return null
        }
        return root.get(index)
    }
}
