package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * One divergence between baseline and candidate responses for a single test case.
 *
 * Pure data — never thrown. Tests record it via DiffCollector and continue;
 * the run fails at @AfterAll on aggregated unclassified records.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DiffRecord(
    val ts: String,
    val endpoint: String,
    val pathParams: Map<String, String>,
    val queryParams: Map<String, String> = emptyMap(),
    val category: DiffClassifier,
    val layer: String,
    val baselineValue: String? = null,
    val candidateValue: String? = null,
    /** Resolved entity identity for triage (e.g. `PRJX / foo @ [1.0,2.0)` or `some-lib @ 12.1.155`). */
    val entityKey: String? = null,
    /**
     * Structured JSON path of the diverging node (raw, with positional indices —
     * e.g. `$[12].component.displayName`). Consumers that need index-stable
     * identity (cluster digest, diff-of-diffs) normalise via
     * [CompatEntityContext.normalizeFieldPath]. Null for layers without a
     * single JSON location (header diffs, typed-layer AssertJ dumps).
     */
    val jsonPath: String? = null,
    val diagnosticHeaders: Map<String, HeaderPair>? = null,
    val message: String? = null,
) {
    fun toJsonLine(mapper: ObjectMapper = DEFAULT_MAPPER): String = mapper.writeValueAsString(this)

    companion object {
        val DEFAULT_MAPPER: ObjectMapper = jacksonObjectMapper()
    }
}

data class HeaderPair(val baseline: String?, val candidate: String?)
