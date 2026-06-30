package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.VersionNamesDTO

/**
 * Five common-controller endpoints under /rest/api/2/common.
 *
 * Large collections (jira-component-version-ranges, dependency-aliases, supported-groups,
 * component-product-mapping) are sorted and sliced to [CompatConfig.maxComponents] elements
 * before comparison so smoke runs stay fast.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommonControllerV2CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `GET jira-component-version-ranges must match`() {
        val endpoint = "GET /rest/api/2/common/jira-component-version-ranges"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/2/common/jira-component-version-ranges")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDtos = runCatching {
                mapper.readValue<Set<JiraComponentVersionRangeDTO>>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDtos = runCatching {
                mapper.readValue<Set<JiraComponentVersionRangeDTO>>(candidate.bodyBytes)
            }.getOrNull()

            val cap = config.maxComponents
            // ADR-018: the decoupled-model re-partitions each component's version ranges; canonicalise
            // (merge adjacent same-payload ranges per component) on BOTH sides so the benign reshaping
            // isn't a VALUE_DIFF. The jira DTOs round-trip faithfully (data class + explicit @JsonCreator).
            val cls = JiraComponentVersionRangeDTO::class.java
            val baselineSliced = sliceCollection(baselineDtos?.toList(), cap)
                ?.let { VersionRangeMapCanonicalizer.canonicalizeTypedRangeArray(it, cls) }
            val candidateSliced = sliceCollection(candidateDtos?.toList(), cap)
                ?.let { VersionRangeMapCanonicalizer.canonicalizeTypedRangeArray(it, cls) }
            compareDto(endpoint, params, baselineSliced, candidateSliced)
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw+typed",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @Test
    fun `GET dependency-aliases must match`() {
        val endpoint = "GET /rest/api/2/common/dependency-aliases"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/2/common/dependency-aliases")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDtos = runCatching {
                mapper.readValue<Map<String, String>>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDtos = runCatching {
                mapper.readValue<Map<String, String>>(candidate.bodyBytes)
            }.getOrNull()

            val cap = config.maxComponents
            val baselineSliced = sliceMap(baselineDtos, cap)
            val candidateSliced = sliceMap(candidateDtos, cap)
            compareDto(endpoint, params, baselineSliced, candidateSliced)
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw+typed",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @Test
    fun `GET supported-groups must match`() {
        val endpoint = "GET /rest/api/2/common/supported-groups"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/2/common/supported-groups")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDtos = runCatching {
                mapper.readValue<Set<String>>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDtos = runCatching {
                mapper.readValue<Set<String>>(candidate.bodyBytes)
            }.getOrNull()

            val cap = config.maxComponents
            val baselineSliced = sliceCollection(baselineDtos?.toList(), cap)
            val candidateSliced = sliceCollection(candidateDtos?.toList(), cap)
            compareDto(endpoint, params, baselineSliced, candidateSliced)
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw+typed",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @Test
    fun `GET component-product-mapping must match`() {
        val endpoint = "GET /rest/api/2/common/component-product-mapping"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/2/common/component-product-mapping")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDtos = runCatching {
                mapper.readValue<Map<String, ProductTypes>>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDtos = runCatching {
                mapper.readValue<Map<String, ProductTypes>>(candidate.bodyBytes)
            }.getOrNull()

            val cap = config.maxComponents
            val baselineSliced = sliceMap(baselineDtos, cap)
            val candidateSliced = sliceMap(candidateDtos, cap)
            compareDto(endpoint, params, baselineSliced, candidateSliced)
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
            layer = "raw+typed",
            diffsBefore = before,
            diffsAfter = after,
        )
    }

    @Test
    fun `GET version-names must match`() {
        val endpoint = "GET /rest/api/2/common/version-names"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/2/common/version-names")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching {
                mapper.readValue<VersionNamesDTO>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDto = runCatching {
                mapper.readValue<VersionNamesDTO>(candidate.bodyBytes)
            }.getOrNull()
            compareDto(endpoint, params, baselineDto, candidateDto)
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            baseline = baseline,
            candidate = candidate,
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

    /**
     * Stable slice of a list: sort by [Any.toString], take first [cap] elements.
     * Returns the full list when [cap] is null or the input is null.
     */
    private fun <T> sliceCollection(list: List<T>?, cap: Int?): List<T>? {
        if (list == null) return null
        if (cap == null) return list
        return list.sortedBy { it.toString() }.take(cap)
    }

    /**
     * Stable slice of a map: sort entries by key, take first [cap] entries, re-assemble.
     * Returns the full map when [cap] is null or the input is null.
     */
    private fun <V> sliceMap(map: Map<String, V>?, cap: Int?): Map<String, V>? {
        if (map == null) return null
        if (cap == null) return map
        return map.entries.sortedBy { it.key }.take(cap).associate { it.key to it.value }
    }
}
