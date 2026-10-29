package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import java.util.stream.Stream

/**
 * GET /rest/api/2/components/{component}/versions/{version} → DetailedComponent.
 *
 * Drives the version-bearing pattern. For each smoke-component, [VersionSampler] returns a few
 * real release versions from RMS. Test pair is (component, version); both stands hit; raw +
 * typed comparison.
 *
 * If a component has no RMS releases: tests for it skip with a recorded "no versions" execution
 * entry. (Coverage gate is enforced separately — see plan §RMS coverage guard.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetailedComponentV2CompatTest : CompatibilityTestBase() {
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/versions/{1}")
    @MethodSource("componentVersionPairs")
    fun `GET v2 detailed component must match per component-version`(
        componentName: String,
        version: String,
    ) {
        skipIfNoSmokeConfig(componentName, version)
        val endpoint = "GET /rest/api/2/components/{component}/versions/{version}"
        val params = mapOf("component" to componentName, "version" to version)
        val (baseline, candidate) = fetchPair("/rest/api/2/components/$componentName/versions/$version")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<DetailedComponent>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<DetailedComponent>(candidate.bodyBytes) }.getOrNull()
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
     * Cartesian product (component, version) for parameterised tests.
     * Versions are fetched from RMS once per component (cached).
     */
    private fun componentVersionPairs(): Stream<Arguments> {
        val components = smokeComponents()
        // Smoke: 1 latest release; full: 5. Honoured via VersionSampler limit param.
        val limit = if (config.full) 5 else 1
        val pairs = components.flatMap { c ->
            val versions = VersionSampler.versionsFor(c, limit = limit)
            if (versions.isEmpty()) {
                log.warn("No real release versions for {} from RMS — skipping versioned tests", c)
                emptyList()
            } else {
                versions.map { v -> c to v }
            }
        }
        return pairArgsOrSentinel(pairs)
    }
}
