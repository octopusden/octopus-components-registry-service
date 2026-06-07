package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import java.util.stream.Stream

/**
 * Targeted gate for the ~50-diff jira-ranges + per-version distribution cluster
 * observed on TC [1.7] builds #3826 / #3834. Exercised by TeamCity [1.9]
 * (`id19CompatClusterGateAuto`) before the full [1.7] matrix.
 *
 * The target project keys and component:version pairs are confidential
 * (open-source redaction rule) and are supplied at runtime:
 *   - `compat.cluster.project-keys` / `COMPAT_CLUSTER_PROJECT_KEYS` — CSV of
 *     jira project keys for the jira-ranges part;
 *   - `compat.cluster.distribution-pairs` / `COMPAT_CLUSTER_DISTRIBUTION_PAIRS`
 *     — CSV of `component:version` pairs for the distribution part.
 * On CI the values come from TC server-side project parameters (exported by the
 * id19 build step); locally from a private env file. Missing/blank inputs are a
 * hard test failure (fail-fast) — a silently empty gate would go green while
 * checking nothing. Only counts are ever logged, never the values.
 *
 * DISABLED (2026-06-07): this was a fast narrow pre-check for the historical
 * ~50-diff cluster; the full [1.7] sweep now covers the same endpoints across
 * the 30k trace slice and is the authoritative gate. Kept (not deleted) so the
 * triage tooling and env contract stay documented; re-enable by removing
 * `@Disabled` if a dedicated cluster gate is wanted again. Once the full sweeps
 * are stably green this class and the id19 [1.9] config can be removed outright.
 */
@Disabled("Superseded by the full [1.7] sweep; see class KDoc")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cluster-50 compat gate (jira-ranges + per-version distribution)")
class Cluster50CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    // Indexed display name only — the project key is confidential and must not
    // leak into JUnit XML/HTML report names (counts-only policy of this class).
    @ParameterizedTest(name = "[{index}] GET /rest/api/2/projects/<key>/jira-component-version-ranges")
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

    // Indexed display name only — component:version pairs are confidential
    // (same counts-only policy as above).
    @ParameterizedTest(name = "[{index}] GET /rest/api/2/components/<c>/versions/<v>/distribution")
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
        /**
         * Resolve a confidential cluster input from a system property or its
         * UPPER_SNAKE env twin. Fail-fast on missing/blank: a silently empty
         * gate would pass while checking nothing.
         */
        private fun clusterInput(prop: String): String {
            val env = prop.uppercase().replace('.', '_').replace('-', '_')
            val value = System.getProperty(prop) ?: System.getenv(env)
            check(!value.isNullOrBlank()) {
                "$prop / $env must be set (confidential cluster inputs come from " +
                    "TC server-side project parameters on CI or a private env file locally)"
            }
            return value
        }

        private val clusterProjectKeys: List<String> by lazy {
            val keys =
                clusterInput("compat.cluster.project-keys")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            check(keys.isNotEmpty()) { "compat.cluster.project-keys produced 0 project keys" }
            // Counts only — never log the values (confidential).
            println("[cluster-50] projectKeys=${keys.size}")
            keys
        }

        private val clusterDistributionPairs: List<Pair<String, String>> by lazy {
            val pairs =
                clusterInput("compat.cluster.distribution-pairs")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { token ->
                        val sep = token.indexOf(':')
                        check(sep in 1 until token.length - 1) {
                            "compat.cluster.distribution-pairs entries must be component:version " +
                                "(a malformed entry was found; value not printed — confidential)"
                        }
                        token.substring(0, sep) to token.substring(sep + 1)
                    }
            check(pairs.isNotEmpty()) { "compat.cluster.distribution-pairs produced 0 pairs" }
            println("[cluster-50] distributionPairs=${pairs.size}")
            pairs
        }

        @JvmStatic
        fun projectKeyArgs(): Stream<Arguments> =
            clusterProjectKeys.stream().map { Arguments.of(it) }

        @JvmStatic
        fun distributionVersionPairs(): Stream<Arguments> =
            clusterDistributionPairs.stream().map { (c, v) -> Arguments.of(c, v) }
    }
}
