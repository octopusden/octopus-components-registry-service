package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import java.util.stream.Stream

/**
 * GET /rest/api/2/components/{component}/versions/{version}/vcs-settings → VCSSettingsDTO.
 *
 * For each smoke-component, [VersionSampler] returns real release versions from RMS.
 * Both stands are hit; raw + typed comparison.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VcsSettingsV2CompatTest : CompatibilityTestBase() {
    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/versions/{1}/vcs-settings")
    @MethodSource("componentVersionPairs")
    fun `GET v2 vcs settings must match per component-version`(
        componentName: String,
        version: String,
    ) {
        skipIfNoSmokeConfig(componentName, version)
        val endpoint = "GET /rest/api/2/components/{component}/versions/{version}/vcs-settings"
        val params = mapOf("component" to componentName, "version" to version)
        val (baseline, candidate) = fetchPair("/rest/api/2/components/$componentName/versions/$version/vcs-settings")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<VCSSettingsDTO>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<VCSSettingsDTO>(candidate.bodyBytes) }.getOrNull()
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

    private fun componentVersionPairs(): Stream<Arguments> {
        val components = smokeComponents()
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
