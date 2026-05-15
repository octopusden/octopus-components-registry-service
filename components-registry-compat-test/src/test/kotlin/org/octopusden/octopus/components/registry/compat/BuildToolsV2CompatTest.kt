package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import java.util.stream.Stream

/**
 * GET /rest/api/2/components/{component}/versions/{version}/build-tools?ignore-required={bool} → List<BuildTool>.
 *
 * For each smoke-component, [VersionSampler] returns real release versions from RMS.
 * The `ignore-required` query param is exercised with both `true` and `false` values.
 * Both stands are hit; raw + typed comparison.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildToolsV2CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/versions/{1}/build-tools?ignore-required={2}")
    @MethodSource("componentVersionIgnoreRequiredTriples")
    fun `GET v2 build tools must match per component-version-ignoreRequired`(
        componentName: String,
        version: String,
        ignoreRequired: Boolean,
    ) {
        skipIfNoSmokeConfig(componentName, version)
        val endpoint = "GET /rest/api/2/components/{component}/versions/{version}/build-tools"
        val params = mapOf("component" to componentName, "version" to version)
        val queryParams = mapOf("ignore-required" to ignoreRequired.toString())
        val path = "/rest/api/2/components/$componentName/versions/$version/build-tools?ignore-required=$ignoreRequired"
        val (baseline, candidate) = fetchPair(path)

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate, queryParams = queryParams)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<List<BuildTool>>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<List<BuildTool>>(candidate.bodyBytes) }.getOrNull()
            compareDto(endpoint, params, baselineDto, candidateDto, queryParams = queryParams)
        }

        val after = DiffCollector.count()
        logExecution(
            endpoint = endpoint,
            pathParams = params,
            queryParams = queryParams,
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

    private fun componentVersionIgnoreRequiredTriples(): Stream<Arguments> {
        val components = smokeComponents()
        val limit = if (config.full) 5 else 1
        val triples = components.flatMap { c ->
            val versions = VersionSampler.versionsFor(c, limit = limit)
            if (versions.isEmpty()) {
                log.warn("No real release versions for {} from RMS — skipping versioned tests", c)
                emptyList()
            } else {
                versions.flatMap { v ->
                    listOf(
                        Arguments.of(c, v, false),
                        Arguments.of(c, v, true),
                    )
                }
            }
        }
        if (triples.isEmpty()) {
            return Stream.of(Arguments.of(NO_SMOKE_CONFIG, NO_SMOKE_CONFIG, false))
        }
        return triples.stream()
    }
}
