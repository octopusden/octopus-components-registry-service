package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import java.util.stream.Stream

/**
 * GET /rest/api/2/components/{component} — component-detail endpoint inherited from
 * BaseComponentController on V2.
 *
 * No version parameter. One request per smoke-component, raw + typed comparison.
 * Typed layer decodes the body directly to [ComponentV2] (no Feign method exists for V2 path —
 * the Feign client only wraps the v1 variant of this URL — so we Jackson-decode by hand).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentDetailV2CompatTest : CompatibilityTestBase() {
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}")
    @MethodSource("smokeComponentArgs")
    fun `GET v2 component detail must match per component`(componentName: String) {
        skipIfNoSmokeConfig(componentName)
        val endpoint = "GET /rest/api/2/components/{component}"
        val params = mapOf("component" to componentName)
        val (baseline, candidate) = fetchPair("/rest/api/2/components/$componentName")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        // Typed layer: decode raw JSON to ComponentV2 ourselves; AssertJ recursive compare.
        // Skipped if either side returned non-2xx (the raw layer already recorded the diff).
        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<ComponentV2>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<ComponentV2>(candidate.bodyBytes) }.getOrNull()
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
