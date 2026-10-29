package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import java.util.stream.Stream

/**
 * GET /rest/api/2/components with various query-parameter combinations.
 *
 * Covers: no params, vcs-path filter, build-system filter, solution=true, solution=false.
 * Response decoded as [ComponentsDTO]<[ComponentV2]>. Lists are sorted by component id and
 * capped at [CompatConfig.maxComponents] before recursive comparison so smoke runs stay fast.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentsListV2CompatTest : CompatibilityTestBase() {
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/components?{0}")
    @MethodSource("queryVariants")
    fun `GET v2 components list must match per query variant`(
        label: String,
        queryString: String,
    ) {
        val path = if (queryString.isEmpty()) "/rest/api/2/components" else "/rest/api/2/components?$queryString"
        val endpoint = "GET /rest/api/2/components"
        val params = mapOf("query" to label)

        val (baselineResp, candidateResp) = fetchPair(path)

        val cap = config.maxComponents
        val baselineSliced = sliceJsonArrayByComponentId(baselineResp.json, cap)
        val candidateSliced = sliceJsonArrayByComponentId(candidateResp.json, cap)

        val before = DiffCollector.count()

        val baselineForDiff = baselineResp.copy(json = baselineSliced ?: baselineResp.json)
        val candidateForDiff = candidateResp.copy(json = candidateSliced ?: candidateResp.json)
        compareRaw(endpoint, params, baselineForDiff, candidateForDiff)

        if (baselineResp.status in 200..299 && candidateResp.status in 200..299) {
            val baselineDtos = runCatching {
                mapper.readValue<ComponentsDTO<ComponentV2>>(baselineResp.bodyBytes).components.toList()
            }.getOrNull()
            val candidateDtos = runCatching {
                mapper.readValue<ComponentsDTO<ComponentV2>>(candidateResp.bodyBytes).components.toList()
            }.getOrNull()

            val baselineById = (sliceDtosByComponentId(baselineDtos, cap) ?: emptyList())
                .associateBy { it.id }
            val candidateById = (sliceDtosByComponentId(candidateDtos, cap) ?: emptyList())
                .associateBy { it.id }
            val allIds = (baselineById.keys + candidateById.keys).toSortedSet()
            for (id in allIds) {
                val perCompParams = params + ("componentId" to id)
                compareDto(endpoint, perCompParams, baselineById[id], candidateById[id])
            }
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            queryParams = mapOf("q" to queryString),
            baseline = baselineResp,
            candidate = candidateResp,
            layer = "raw+typed",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @AfterAll
    fun closeStreams() {
        DiffCollector.close()
        ExecutionLogger.close()
    }

    private fun sliceJsonArrayByComponentId(
        json: JsonNode?,
        cap: Int?,
    ): JsonNode? {
        if (cap == null) return null
        if (json == null) return null
        // The /components endpoint returns {"components": [...]} wrapper — extract the array.
        val arr: ArrayNode? = when {
            json.isArray -> json as ArrayNode
            json.has("components") && json.get("components").isArray -> json.get("components") as ArrayNode
            else -> return null
        }
        arr ?: return null
        val sorted = arr.toList().sortedBy { it.path("id").asText("") }
        val sliced = sorted.take(cap)
        val out = JsonNodeFactory.instance.arrayNode(sliced.size)
        sliced.forEach { out.add(it) }
        return out
    }

    private fun sliceDtosByComponentId(
        dtos: List<ComponentV2>?,
        cap: Int?,
    ): List<ComponentV2>? {
        if (dtos == null) return null
        if (cap == null) return dtos
        return dtos.sortedBy { it.id }.take(cap)
    }

    companion object {
        @JvmStatic
        fun queryVariants(): Stream<Arguments> =
            Stream.of(
                Arguments.of("no-params", ""),
                // Synthetic (any non-empty value reproduces the bug — see /tmp/crs-prod-report.txt
                // for real prod queries hitting this endpoint with vcs-path=ssh://git@bitbucket.../...).
                Arguments.of("vcs-path-synthetic", "vcs-path=ssh%3A%2F%2Fhg%40mercurial"),
                // Real prod queries observed in the 5-day access log (97 hits each).
                // Hostnames + project keys + repo names are redacted — the URL-encoded
                // shape is what matters for the backend's vcs-path parsing; only the
                // structural shape (depth, separators, escape sequences) is significant.
                Arguments.of(
                    "vcs-path-prod-shape-a",
                    "vcs-path=ssh%3A%2F%2Fgit%40bitbucket.example.com%2FPROJECT_A%2Fmodule-a.git",
                ),
                Arguments.of(
                    "vcs-path-prod-shape-b",
                    "vcs-path=ssh%3A%2F%2Fgit%40bitbucket.example.com%2FPROJECT_B%2Frepo-b.git",
                ),
                // Java Hashtable.toString() leak observed in prod (80 hits / 5 days):
                // a client puts the result of toString() into the query string. Schema-v2 candidate
                // must tolerate it (baseline returns 200 with empty filter).
                Arguments.of(
                    "systems-hashtable-leak-1",
                    "systems&serialVersionUID=-7390468764508069838",
                ),
                Arguments.of(
                    "systems-hashtable-leak-2",
                    "systems&modCount=0&serialVersionUID=8842843931221139166",
                ),
                Arguments.of("build-system-MAVEN", "build-system=MAVEN"),
                Arguments.of("solution-true", "solution=true"),
                Arguments.of("solution-false", "solution=false"),
            )
    }
}
