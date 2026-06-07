package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode

/**
 * Resolves human-readable entity keys for compat diff records.
 *
 * Raw-layer STRUCTURAL_DIFF messages use positional JSON paths (`$[3].field`).
 * After [RawArraySorters] alignment those indices are stable but opaque — this
 * helper maps them back to `(componentName, versionRange)` for cluster triage.
 *
 * Endpoint identity comes in TWO spellings: the templated form used by the
 * endpoint-specific suites (`…/projects/{projectKey}/…`, with `pathParams`)
 * and the RAW form used by trace/residual replay (`…/projects/PRJX/…`, empty
 * `pathParams`). [canonicalEndpoint] folds the raw spelling onto the template
 * so the sorter registry and the entity resolution treat both identically —
 * before this, the raw spelling fell through both (no pre-sort → positional
 * false-positives; no entity key → diff-of-diffs keys collapsed).
 */
object CompatEntityContext {
    private val arrayIndex = Regex("""\$\[(\d+)\]""")

    private val perProjectJiraRangesRaw =
        Regex("""^GET /rest/api/2/projects/([^/{}]+)/jira-component-version-ranges$""")
    private val perVersionComponentRaw =
        Regex("""^[A-Z]+ /rest/api/\d+/components/([^/{}]+)/versions/([^/{}]+)(?:/.*)?$""")

    private const val PER_PROJECT_JIRA_RANGES_TEMPLATE =
        "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges"

    private val jiraRangesEndpoints =
        setOf(
            "GET /rest/api/2/common/jira-component-version-ranges",
            PER_PROJECT_JIRA_RANGES_TEMPLATE,
        )

    /**
     * Fold a RAW endpoint spelling (trace/residual replay) onto the templated
     * form the registries are keyed on. Identity for everything else.
     */
    fun canonicalEndpoint(endpoint: String): String =
        if (perProjectJiraRangesRaw.matches(endpoint)) {
            PER_PROJECT_JIRA_RANGES_TEMPLATE
        } else {
            endpoint
        }

    fun resolveEntityKey(
        endpoint: String,
        jsonPath: String,
        pathParams: Map<String, String>,
        baselineJson: JsonNode?,
        candidateJson: JsonNode?,
    ): String? {
        val canonical = canonicalEndpoint(endpoint)
        if (canonical in jiraRangesEndpoints) {
            val index = arrayIndex.find(jsonPath)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val element =
                elementAt(RawArraySorters.stableSorted(endpoint, baselineJson), index)
                    ?: elementAt(RawArraySorters.stableSorted(endpoint, candidateJson), index)
                    ?: return null
            val componentName = element.path("componentName").asText("").ifBlank { "?" }
            val versionRange = element.path("versionRange").asText("").ifBlank { "?" }
            val project =
                pathParams["projectKey"]?.takeIf { it.isNotBlank() }
                    ?: perProjectJiraRangesRaw.find(endpoint)?.groupValues?.get(1)
            return if (project == null) {
                "$componentName @ $versionRange"
            } else {
                "$project / $componentName @ $versionRange"
            }
        }
        val component =
            pathParams["component"]?.takeIf { it.isNotBlank() }
                ?: perVersionComponentRaw.find(endpoint)?.groupValues?.get(1)
        val version =
            pathParams["version"]?.takeIf { it.isNotBlank() }
                ?: perVersionComponentRaw.find(endpoint)?.groupValues?.get(2)
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
