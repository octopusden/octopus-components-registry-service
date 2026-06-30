package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import java.util.stream.Stream

/**
 * GET /rest/api/2/components/{component}/maven-artifacts
 * → [Map]<[String], [ComponentArtifactConfigurationDTO]>
 *
 * Smoke-component-driven (no version parameter). For each component in smoke list,
 * fetch both stands, compare raw shapes, then decode and compare the typed map.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MavenArtifactsV2CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/maven-artifacts")
    @MethodSource("smokeComponentArgs")
    fun `GET v2 maven-artifacts must match per component`(componentName: String) {
        skipIfNoSmokeConfig(componentName)
        val endpoint = "GET /rest/api/2/components/{component}/maven-artifacts"
        val params = mapOf("component" to componentName)
        val (baseline, candidate) = fetchPair("/rest/api/2/components/$componentName/maven-artifacts")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching {
                mapper.readValue<Map<String, ComponentArtifactConfigurationDTO>>(baseline.bodyBytes)
            }.getOrNull()
            val candidateDto = runCatching {
                mapper.readValue<Map<String, ComponentArtifactConfigurationDTO>>(candidate.bodyBytes)
            }.getOrNull()
            // ADR-018: the root map is keyed by version range; the decoupled-model read path re-partitions
            // it (whitespace / composite-split / adjacent-merge / version-form) vs V1's verbatim DSL keys.
            // Canonicalise the KEYS on both sides (values stay typed, so compareDto's artifactPattern
            // normaliser still applies and a real ownership change still surfaces). See VersionRangeMapCanonicalizer.
            compareDto(
                endpoint,
                params,
                baselineDto?.let { VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(it) },
                candidateDto?.let { VersionRangeMapCanonicalizer.canonicalizeTypedRangeMap(it) },
            )
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
