package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import java.util.stream.Stream

/**
 * POST /rest/api/2/components/{component}/detailed-versions → DetailedComponentVersions.
 *
 * For each smoke-component, [VersionSampler] returns real release versions from RMS.
 * Those versions are packed into a [VersionRequest] and POSTed to both stands.
 * Components for which [VersionSampler] returns an empty list are skipped (warning logged).
 *
 * Smoke run: 1 version per component. Full run: 5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetailedVersionsBatchV2CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "POST /rest/api/2/components/{0}/detailed-versions versions={1}")
    @MethodSource("smokeComponentsArgs")
    fun `POST v2 detailed-versions must match per component`(componentName: String, versions: List<String>) {
        skipIfNoSmokeConfig(componentName)
        val endpoint = "POST /rest/api/2/components/{component}/detailed-versions"
        val params = mapOf("component" to componentName)
        val body = VersionRequest(versions = versions)
        val (baseline, candidate) = postJsonPair("/rest/api/2/components/$componentName/detailed-versions", body)

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching {
                mapper.readValue<DetailedComponentVersions>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDto = runCatching {
                mapper.readValue<DetailedComponentVersions>(candidate.bodyBytes)
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
     * For each smoke-component, discover versions via [VersionSampler] and yield
     * (componentName, versions) pairs. Components with no RMS releases are skipped.
     */
    private fun smokeComponentsArgs(): Stream<Arguments> {
        val limit = if (config.full) 5 else 1
        val items = smokeComponents().flatMap { component ->
            val versions = VersionSampler.versionsFor(component, limit = limit)
            if (versions.isEmpty()) {
                log.warn("No real release versions for {} from RMS — skipping detailed-versions batch test", component)
                emptyList()
            } else {
                listOf(Arguments.of(component, versions))
            }
        }
        if (items.isEmpty()) {
            // Sentinel matches the (componentName, versions) shape so JUnit doesn't
            // fail at parameterized-test discovery; the test body skips via
            // skipIfNoSmokeConfig with a clear message.
            return Stream.of(Arguments.of(NO_SMOKE_CONFIG, emptyList<String>()))
        }
        return items.stream()
    }
}
