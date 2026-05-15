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
    val diagnosticHeaders: Map<String, HeaderPair>? = null,
    val message: String? = null,
) {
    fun toJsonLine(mapper: ObjectMapper = DEFAULT_MAPPER): String = mapper.writeValueAsString(this)

    companion object {
        val DEFAULT_MAPPER: ObjectMapper = jacksonObjectMapper()
    }
}

data class HeaderPair(val baseline: String?, val candidate: String?)
