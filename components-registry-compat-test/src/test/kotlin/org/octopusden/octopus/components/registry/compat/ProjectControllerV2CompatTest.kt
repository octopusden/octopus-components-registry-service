package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import java.util.stream.Stream

/**
 * Six endpoints under /rest/api/2/projects/{projectKey}/...
 *
 * Project keys are discovered at [discoverProjects] by calling
 * GET /rest/api/2/common/jira-component-version-ranges on the baseline stand,
 * extracting distinct projectKey values, and capping at [CompatConfig.maxComponents]
 * (default 3 for smoke). Falls back to [FALLBACK_PROJECTS] if discovery fails.
 *
 * Version-bearing endpoints use [PROBE_VERSION]; 404 from either stand is silently
 * tolerated (STATUS_CODE_DIFF recorded only when sides differ).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectControllerV2CompatTest : CompatibilityTestBase() {

    private val mapper = jacksonObjectMapper()

    /** Discovered (or fallback) project keys shared by all test methods. */
    private var projectKeys: List<String> = FALLBACK_PROJECTS

    @BeforeAll
    fun discoverProjects() {
        // verifyConfigAndInit() runs first (declared in CompatibilityTestBase); if compat is
        // inactive the test will have already been skipped via assumeTrue, so baselineRaw is safe
        // to use here. We wrap in runCatching to degrade gracefully if discovery fails.
        val discovered = runCatching {
            val resp = baselineRaw.get("/rest/api/2/common/jira-component-version-ranges")
            if (resp.status !in 200..299) {
                log.warn("Project discovery returned HTTP {}; falling back to hardcoded list", resp.status)
                return@runCatching emptyList()
            }
            val ranges: Set<JiraComponentVersionRangeDTO> =
                mapper.readValue(resp.bodyBytes)
            ranges
                .map { it.component.projectKey }
                .distinct()
                .sorted()
        }.onFailure { ex ->
            log.warn("Project discovery failed ({}); falling back to hardcoded list", ex.message)
        }.getOrDefault(emptyList())

        // Hard cap: project keys are independent of components — using config.maxComponents
        // would drag in 10+ projects and saturate the gateway with 90+ requests per smoke run.
        // Smoke uses MAX_PROJECTS (=3); full run scales linearly via PROBE_VERSIONS.
        val cap = if (config.full) (config.maxComponents ?: MAX_PROJECTS) else MAX_PROJECTS
        projectKeys = if (discovered.isEmpty()) {
            FALLBACK_PROJECTS
        } else {
            discovered.take(cap)
        }
        log.info("ProjectControllerV2CompatTest will exercise project keys: {}", projectKeys)
    }

    // -------------------------------------------------------------------------
    // No-version endpoints
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "GET /rest/api/2/projects/{0}/jira-components")
    @MethodSource("projectKeyArgs")
    fun `GET v2 jira-components by project must match`(projectKey: String) {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/jira-components"
        val params = mapOf("projectKey" to projectKey)
        val (baseline, candidate) = fetchPair("/rest/api/2/projects/$projectKey/jira-components")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto = runCatching { mapper.readValue<Set<String>>(baseline.bodyBytes) }.getOrNull()
            val candidateDto = runCatching { mapper.readValue<Set<String>>(candidate.bodyBytes) }.getOrNull()
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

    @ParameterizedTest(name = "GET /rest/api/2/projects/{0}/component-distributions")
    @MethodSource("projectKeyArgs")
    fun `GET v2 component-distributions by project must match`(projectKey: String) {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/component-distributions"
        val params = mapOf("projectKey" to projectKey)
        val (baseline, candidate) = fetchPair("/rest/api/2/projects/$projectKey/component-distributions")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto =
                runCatching { mapper.readValue<Map<String, DistributionDTO>>(baseline.bodyBytes) }.getOrNull()
            val candidateDto =
                runCatching { mapper.readValue<Map<String, DistributionDTO>>(candidate.bodyBytes) }.getOrNull()
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

    // -------------------------------------------------------------------------
    // Version-bearing endpoints — probed with PROBE_VERSION; 404 symmetry asserted
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "GET /rest/api/2/projects/{0}/versions/{1}")
    @MethodSource("projectKeyVersionArgs")
    fun `GET v2 project version jira-component must match`(projectKey: String, version: String) {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/versions/{version}"
        val params = mapOf("projectKey" to projectKey, "version" to version)
        val (baseline, candidate) = fetchPair("/rest/api/2/projects/$projectKey/versions/$version")

        val before = DiffCollector.count()
        compareRaw(endpoint, params, baseline, candidate)

        if (baseline.status in 200..299 && candidate.status in 200..299) {
            val baselineDto =
                runCatching { mapper.readValue<JiraComponentVersionDTO>(baseline.bodyBytes) }.getOrNull()
            val candidateDto =
                runCatching { mapper.readValue<JiraComponentVersionDTO>(candidate.bodyBytes) }.getOrNull()
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

    @ParameterizedTest(name = "GET /rest/api/2/projects/{0}/versions/{1}/vcs-settings")
    @MethodSource("projectKeyVersionArgs")
    fun `GET v2 project version vcs-settings must match`(projectKey: String, version: String) {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/versions/{version}/vcs-settings"
        val params = mapOf("projectKey" to projectKey, "version" to version)
        val (baseline, candidate) = fetchPair("/rest/api/2/projects/$projectKey/versions/$version/vcs-settings")

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

    @ParameterizedTest(name = "GET /rest/api/2/projects/{0}/versions/{1}/distribution")
    @MethodSource("projectKeyVersionArgs")
    fun `GET v2 project version distribution must match`(projectKey: String, version: String) {
        val endpoint = "GET /rest/api/2/projects/{projectKey}/versions/{version}/distribution"
        val params = mapOf("projectKey" to projectKey, "version" to version)
        val (baseline, candidate) = fetchPair("/rest/api/2/projects/$projectKey/versions/$version/distribution")

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

    // -------------------------------------------------------------------------
    // MethodSource providers
    // -------------------------------------------------------------------------

    private fun projectKeyArgs(): Stream<Arguments> =
        projectKeys.map { Arguments.of(it) }.stream()

    /**
     * Cartesian product of (projectKey, version) using [PROBE_VERSION].
     * 404 symmetry is the assertion: if both sides 404, no diff recorded.
     */
    private fun projectKeyVersionArgs(): Stream<Arguments> =
        projectKeys.flatMap { pk ->
            PROBE_VERSIONS.map { v -> Arguments.of(pk, v) }
        }.stream()

    companion object {
        /** Max number of project keys to exercise in smoke mode (no explicit maxComponents). */
        private const val MAX_PROJECTS = 3

        /** Hardcoded fallback project keys used when discovery fails. */
        private val FALLBACK_PROJECTS = listOf("CRS", "TC3")

        /**
         * Probe versions for version-bearing endpoints. Using a small set so that at least
         * one real version is likely covered while keeping test count low.
         * 404-symmetry is asserted: equal status on both sides means no diff recorded.
         */
        private val PROBE_VERSIONS = listOf("1.0", "2.0")
    }
}
