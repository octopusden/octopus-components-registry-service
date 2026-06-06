package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import java.util.stream.Stream

/**
 * Targeted gate for the ~50-diff CARDS/ANCS jira-ranges + authmodlib distribution
 * cluster observed on TC [1.7] builds #3826 / #3834. Exercised by TeamCity [1.9]
 * (`id19CompatClusterGateAuto`) before the full [1.7] matrix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cluster-50 compat gate (CARDS/ANCS jira-ranges + authmodlib distribution)")
class Cluster50CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    @ParameterizedTest(name = "GET /rest/api/2/projects/{0}/jira-component-version-ranges")
    @MethodSource("projectKeyArgs")
    fun `GET v2 jira-component-version-ranges by project must match`(projectKey: String) {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/jira-component-version-ranges"
        val params = mapOf("projectKey" to projectKey)
        val (baseline, candidate) = fetchPair("/rest/api/2/projects/$projectKey/jira-component-version-ranges")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto =
                runCatching { mapper.readValue<Set<JiraComponentVersionRangeDTO>>(baseline.bodyBytes) }.getOrNull()
            val candidateDto =
                runCatching { mapper.readValue<Set<JiraComponentVersionRangeDTO>>(candidate.bodyBytes) }.getOrNull()
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

    @ParameterizedTest(name = "GET /rest/api/2/components/{0}/versions/{1}/distribution")
    @MethodSource("distributionVersionPairs")
    fun `GET v2 per-version distribution must match`(componentName: String, version: String) {
        val endpoint = "GET /rest/api/2/components/{component}/versions/{version}/distribution"
        val params = mapOf("component" to componentName, "version" to version)
        val path = "/rest/api/2/components/$componentName/versions/$version/distribution"
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

    companion object {
        private val CLUSTER_PROJECT_KEYS = listOf("CARDS", "ANCS")
        private val CLUSTER_DISTRIBUTION_PAIRS =
            listOf(
                "authmodlib" to "12.1.155",
                "authmodlib" to "12.1.156",
            )

        @JvmStatic
        fun projectKeyArgs(): Stream<Arguments> =
            CLUSTER_PROJECT_KEYS.stream().map { Arguments.of(it) }

        @JvmStatic
        fun distributionVersionPairs(): Stream<Arguments> =
            CLUSTER_DISTRIBUTION_PAIRS.stream().map { (c, v) -> Arguments.of(c, v) }
    }
}
