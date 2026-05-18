package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octopusden.octopus.components.registry.core.dto.ComponentV3

/**
 * First end-to-end compat test: GET /rest/api/3/components against both stands.
 *
 * Validates the whole pipeline:
 *  - Config loading + assumption skip
 *  - Raw HTTP layer + JSON shape diff
 *  - Typed Feign client + DTO recursive comparison
 *  - DiffCollector + ExecutionLogger writing to per-worker ndjson
 *  - Gradle reporter aggregating + failing on diffs
 *
 * Honours [CompatConfig.maxComponents] — both lists are sliced to the first N (sorted by
 * component id) before comparison. Default for smoke is 10; full runs use the whole list.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentsListCompatTest : CompatibilityTestBase() {

    @Test
    fun `GET rest_api_3_components — list shape and DTO values must match`() {
        val endpoint = "GET /rest/api/3/components"
        val (baselineResp, candidateResp) = fetchPair("/rest/api/3/components")

        val cap = config.maxComponents
        val baselineSliced = sliceJsonArrayByComponentId(baselineResp.json, cap)
        val candidateSliced = sliceJsonArrayByComponentId(candidateResp.json, cap)

        val before = DiffCollector.count()
        // Re-wrap into RawResponses so compareRaw can do shape diffs on the (possibly sliced) bodies.
        val baselineForDiff = baselineResp.copy(json = baselineSliced ?: baselineResp.json)
        val candidateForDiff = candidateResp.copy(json = candidateSliced ?: candidateResp.json)
        val params = mapOf("limit" to (cap?.toString() ?: "all"))
        compareRaw(endpoint, params, baselineForDiff, candidateForDiff)

        // Typed layer: decode through Feign, sort by component.id, slice, then compare each
        // component INDIVIDUALLY. AssertJ's recursive comparison gives a precise field path for
        // a single object; comparing two whole lists buries the path inside Object.toString of
        // each element (ComponentV3 has no toString override).
        val baselineDtos = runCatching { baselineTyped.getComponents().toList() }.getOrNull()
        val candidateDtos = runCatching { candidateTyped.getComponents().toList() }.getOrNull()
        val baselineById = (sliceDtosByComponentId(baselineDtos, cap) ?: emptyList()).associateBy { it.component.id }
        val candidateById = (sliceDtosByComponentId(candidateDtos, cap) ?: emptyList()).associateBy { it.component.id }
        val allIds = (baselineById.keys + candidateById.keys).toSortedSet()
        for (id in allIds) {
            val perCompParams = params + ("componentId" to id)
            compareDto(endpoint, perCompParams, baselineById[id], candidateById[id])
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baselineResp,
            candidate = candidateResp,
            layer = "raw+typed",
            diffsBefore = before,
            diffsAfter = after,
        )

        log.info(
            "Compared first {} components ({} on baseline, {} on candidate before slicing)",
            cap ?: "all",
            baselineDtos?.size ?: -1,
            candidateDtos?.size ?: -1,
        )
    }

    @AfterAll
    fun closeWorkerStreams() {
        DiffCollector.close()
        ExecutionLogger.close()
    }

    /**
     * Slice a JSON array to the first [cap] elements sorted by `.component.id` (string compare).
     * Returns null if [cap] is null (no slicing) or if [json] is not an array.
     */
    private fun sliceJsonArrayByComponentId(json: JsonNode?, cap: Int?): JsonNode? {
        if (cap == null) return null
        if (json == null || !json.isArray) return null
        val arr = json as ArrayNode
        val sorted = arr.toList().sortedBy { it.path("component").path("id").asText("") }
        val sliced = sorted.take(cap)
        val out = JsonNodeFactory.instance.arrayNode(sliced.size)
        sliced.forEach { out.add(it) }
        return out
    }

    private fun sliceDtosByComponentId(dtos: List<ComponentV3>?, cap: Int?): List<ComponentV3>? {
        if (dtos == null) return null
        if (cap == null) return dtos
        return dtos.sortedBy { it.component.id }.take(cap)
    }
}
