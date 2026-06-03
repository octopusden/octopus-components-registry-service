package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import java.util.stream.Stream

/**
 * v1 components surface (BaseComponentController / ComponentControllerV1):
 *   GET /rest/api/1/components             → [ComponentsDTO]<[ComponentV1]>
 *   GET /rest/api/1/components/{component}  → [ComponentV1]
 *
 * Closes part of #324: only the v2/v3 list/detail had dedicated tests, and the
 * bare v1 list had zero production-trace hits, so the v1 surface was exercised by
 * nothing. v1 is structurally inherited from BaseComponentController but serialises
 * the v1 DTO shape, so a migration regression there is otherwise invisible.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentsListV1CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `GET v1 components list must match`() {
        val endpoint = "GET /rest/api/1/components"
        val params = emptyMap<String, String>()
        val (baseline, candidate) = fetchPair("/rest/api/1/components")
        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)
        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<ComponentsDTO<ComponentV1>>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<ComponentsDTO<ComponentV1>>(candidate.bodyBytes) }.getOrNull()
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

    @ParameterizedTest(name = "GET /rest/api/1/components/{0}")
    @MethodSource("smokeComponentArgs")
    fun `GET v1 component detail must match`(componentName: String) {
        skipIfNoSmokeConfig(componentName)
        val endpoint = "GET /rest/api/1/components/{component}"
        val params = mapOf("component" to componentName)
        val (baseline, candidate) = fetchPair("/rest/api/1/components/$componentName")
        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)
        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<ComponentV1>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<ComponentV1>(candidate.bodyBytes) }.getOrNull()
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

    private fun smokeComponentArgs(): Stream<Arguments> = singleArgsOrSentinel(smokeComponents())
}
