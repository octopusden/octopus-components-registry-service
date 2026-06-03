package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import java.util.stream.Stream

/**
 * Component-level distribution endpoints (declared on BaseComponentController,
 * inherited by v1 + v2), plus the v2 per-version variant:
 *   GET /rest/api/1/components/{component}/distribution
 *   GET /rest/api/2/components/{component}/distribution
 *   GET /rest/api/2/components/{component}/versions/{version}/distribution
 * → [DistributionDTO]
 *
 * Closes part of #324: the component-level distribution has zero production-trace
 * hits and — unlike the `/projects/{p}/.../distribution` variant — had no dedicated
 * test until now.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistributionCompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/1/components/{0}/distribution")
    @MethodSource("smokeComponentArgs")
    fun `GET v1 component distribution must match`(componentName: String) {
        skipIfNoSmokeConfig(componentName)
        runDistribution(
            endpoint = "GET /rest/api/1/components/{component}/distribution",
            path = "/rest/api/1/components/$componentName/distribution",
            params = mapOf("component" to componentName),
        )
    }

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/distribution")
    @MethodSource("smokeComponentArgs")
    fun `GET v2 component distribution must match`(componentName: String) {
        skipIfNoSmokeConfig(componentName)
        runDistribution(
            endpoint = "GET /rest/api/2/components/{component}/distribution",
            path = "/rest/api/2/components/$componentName/distribution",
            params = mapOf("component" to componentName),
        )
    }

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/versions/{1}/distribution")
    @MethodSource("componentVersionPairs")
    fun `GET v2 per-version distribution must match`(componentName: String, version: String) {
        skipIfNoSmokeConfig(componentName, version)
        runDistribution(
            endpoint = "GET /rest/api/2/components/{component}/versions/{version}/distribution",
            path = "/rest/api/2/components/$componentName/versions/$version/distribution",
            params = mapOf("component" to componentName, "version" to version),
        )
    }

    private fun runDistribution(endpoint: String, path: String, params: Map<String, String>) {
        val (baseline, candidate) = fetchPair(path)
        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)
        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<DistributionDTO>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<DistributionDTO>(candidate.bodyBytes) }.getOrNull()
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

    private fun componentVersionPairs(): Stream<Arguments> {
        val limit = if (config.full) 5 else 1
        val pairs =
            smokeComponents().flatMap { c ->
                val versions = VersionSampler.versionsFor(c, limit = limit)
                if (versions.isEmpty()) {
                    log.warn("No real release versions for {} from RMS — skipping versioned distribution tests", c)
                    emptyList()
                } else {
                    versions.map { v -> c to v }
                }
            }
        return pairArgsOrSentinel(pairs)
    }
}
