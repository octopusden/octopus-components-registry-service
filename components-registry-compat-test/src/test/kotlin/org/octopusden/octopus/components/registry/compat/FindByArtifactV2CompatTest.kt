package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import java.util.stream.Stream

/**
 * POST /rest/api/2/components/find-by-artifact → VersionedComponent (single, may 404).
 * POST /rest/api/2/components/findByArtifacts → Collection<VersionedComponent>.
 *
 * Two related endpoints for artifact-based component lookup. Exercised with a small fixed
 * set of ArtifactDependency lookups covering typical groupId/artifactId patterns.
 * 404 symmetry is asserted at the raw layer (STATUS_CODE_DIFF recorded if diverges).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindByArtifactV2CompatTest : CompatibilityTestBase() {
    private val mapper = jacksonObjectMapper()

    /**
     * POST /rest/api/2/components/find-by-artifact — single artifact lookup.
     * Returns VersionedComponent on 2xx, 404 when not found. Symmetry recorded as diff if diverges.
     */
    @ParameterizedTest(name = "POST /rest/api/2/components/find-by-artifact group={0} artifact={1}")
    @MethodSource("artifactLookups")
    fun `POST v2 find-by-artifact must match per artifact`(
        group: String,
        artifactId: String,
        version: String,
    ) {
        val endpoint = "POST /rest/api/2/components/find-by-artifact"
        val params = mapOf("group" to group, "artifact" to artifactId, "version" to version)
        val body = ArtifactDependency(group, artifactId, version)
        val (baseline, candidate) = postJsonPair("/rest/api/2/components/find-by-artifact", body)

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<VersionedComponent>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<VersionedComponent>(candidate.bodyBytes) }.getOrNull()
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

    /**
     * POST /rest/api/2/components/findByArtifacts — batch artifact lookup.
     * Sends all probe artifacts in one collection. Returns Collection<VersionedComponent>.
     */
    @ParameterizedTest(name = "POST /rest/api/2/components/findByArtifacts batch={0}")
    @MethodSource("artifactBatches")
    fun `POST v2 findByArtifacts must match for batch`(
        batchLabel: String,
        artifacts: List<ArtifactDependency>,
    ) {
        val endpoint = "POST /rest/api/2/components/findByArtifacts"
        val params = mapOf("batch" to batchLabel)
        val (baseline, candidate) = postJsonPair("/rest/api/2/components/findByArtifacts", artifacts)

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<List<VersionedComponent>>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<List<VersionedComponent>>(candidate.bodyBytes) }.getOrNull()
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
     * Fixed set of ArtifactDependency probes. Each is a (group, artifactId, version) triple.
     * Synthetic values that exercise the lookup logic without assuming real registry content.
     * A 404 on both sides is fine — STATUS_CODE_DIFF is only recorded when sides disagree.
     */
    private fun artifactLookups(): Stream<Arguments> =
        listOf(
            Arguments.of("org.octopusden.octopus.test", "octopusmpi", "1.2.3"),
            Arguments.of("org.octopusden.octopus", "components-registry-service", "1.0.0"),
        ).stream()

    /**
     * Batches for the findByArtifacts endpoint. Each entry is (label, list-of-artifacts).
     * The single batch sends all probe artifacts together.
     */
    private fun artifactBatches(): Stream<Arguments> {
        val allProbes = listOf(
            ArtifactDependency("org.octopusden.octopus.test", "octopusmpi", "1.2.3"),
            ArtifactDependency("org.octopusden.octopus", "components-registry-service", "1.0.0"),
        )
        return listOf(
            Arguments.of("all-probes", allProbes),
        ).stream()
    }
}
