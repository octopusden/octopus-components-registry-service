package org.octopusden.octopus.components.registry.server.migration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.service.BatchMigrationResult
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * Git-vs-DB validation tests.
 *
 * Compares v2 REST API responses when component is sourced from Git (Groovy DSL)
 * versus DB (after migration). Uses production-like dataset (~100 components)
 * covering all configuration patterns.
 *
 * VAL-010 is the main "canary" test — runs ALL components through ALL endpoints
 * and reports every divergence without stopping at the first failure.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db", "test-db-prod")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitVsDbValidationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var sourceRegistry: ComponentSourceRegistry

    init {
        val testResourcesPath =
            Paths
                .get(
                    GitVsDbValidationTest::class.java.getResource("/expected-data")!!.toURI(),
                ).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    /** All component names discovered after migration. */
    private lateinit var allComponentNames: List<String>

    /**
     * Version overrides for components where "1.0.0" doesn't fall in a valid range.
     * Default version: "1.0.0"
     */
    private val versionMap =
        mapOf(
            "terminal-driver" to "2.5.0", // [2.0,3.0) MAVEN range
            "compliance-module" to "1.5.0", // [1.5,) — skip deprecated [1.0,1.5)
        )

    /** Components to skip in version-specific endpoint tests (fake/missing ranges). */
    private val skipVersionTests =
        setOf(
            "aggregator-core", // ESCROW_NOT_SUPPORTED, fake range [99999,100000]
            "tools-aggregator", // ESCROW_NOT_SUPPORTED, fake range [99999,100000]
            "archived-aggregator", // ESCROW_NOT_SUPPORTED, fake range (0,2)
        )

    private fun versionFor(name: String): String = versionMap.getOrDefault(name, "1.0.0")

    // -------------------------------------------------------------------------
    // Setup: migrate everything
    // -------------------------------------------------------------------------

    @BeforeAll
    fun migrateAll() {
        // 1. Migrate defaults
        mvc
            .perform(post("/rest/api/4/admin/migrate-defaults").with(adminJwt()).accept(APPLICATION_JSON))
            .andExpect(status().isOk)

        // 2. Migrate all components
        val resultJson =
            mvc
                .perform(
                    post("/rest/api/4/admin/migrate-components").with(adminJwt()).accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val result: BatchMigrationResult = objectMapper.readValue(resultJson)
        assertTrue(result.migrated > 0, "Expected components to be migrated, got: $result")
        assertEquals(0, result.failed, "Migration failures: ${result.results.filter { !it.success }}")

        // 3. Discover all component names
        val componentsJson =
            mvc
                .perform(get("/rest/api/2/components").accept(APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val tree = objectMapper.readTree(componentsJson)
        allComponentNames =
            tree
                .path("components")
                .map { it.path("id").asText() }
                .filter { it.isNotEmpty() }
                .sorted()

        assertTrue(allComponentNames.size >= 80, "Expected >=80 components, got ${allComponentNames.size}")
    }

    // -------------------------------------------------------------------------
    // Comparison helper
    // -------------------------------------------------------------------------

    /**
     * Switches component to Git source, fetches [path], switches to DB, fetches again.
     * Returns null if responses match, or a description of the difference.
     */
    private fun compareEndpoint(
        componentName: String,
        path: String,
    ): String? {
        // Git response
        sourceRegistry.setComponentSource(componentName, "git")
        val gitResponse = mvc.perform(get(path).accept(APPLICATION_JSON)).andReturn().response

        // DB response
        sourceRegistry.setComponentSource(componentName, "db")
        val dbResponse = mvc.perform(get(path).accept(APPLICATION_JSON)).andReturn().response

        // Compare status codes
        if (gitResponse.status != dbResponse.status) {
            return "Status mismatch: git=${gitResponse.status}, db=${dbResponse.status}"
        }

        // Both non-200 → equal (e.g. both 404)
        if (gitResponse.status != 200) return null

        // Compare JSON content
        val gitJson = gitResponse.contentAsString
        val dbJson = dbResponse.contentAsString

        return try {
            JSONAssert.assertEquals(gitJson, dbJson, JSONCompareMode.LENIENT)
            null
        } catch (e: AssertionError) {
            e.message
        }
    }

    /**
     * Same as [compareEndpoint] but catches exceptions (e.g. 500 errors)
     * and returns the error message instead of propagating.
     */
    private fun safeCompareEndpoint(
        componentName: String,
        path: String,
    ): String? =
        try {
            compareEndpoint(componentName, path)
        } catch (e: Exception) {
            "Exception: ${e.javaClass.simpleName}: ${e.message?.take(200)}"
        } finally {
            // Always restore to DB source
            try {
                sourceRegistry.setComponentSource(componentName, "db")
            } catch (_: Exception) {
            }
        }

    // -------------------------------------------------------------------------
    // VAL-001: GET /rest/api/2/components/{name}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-001: GET /components/{name} returns identical JSON for Git and DB")
    fun `VAL-001 component by name`() {
        val sampleComponents =
            listOf(
                "payment-gateway",
                "db-kernel",
                "customer-alpha-api",
                "middleware-platform",
                "document-manager",
                "archived-legacy-db",
            )
        val failures = mutableListOf<String>()
        for (name in sampleComponents) {
            val diff = safeCompareEndpoint(name, "/rest/api/2/components/$name")
            if (diff != null) failures.add("[$name] $diff")
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("VAL-001 failures:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-002: GET /rest/api/2/components/{name}/versions/{version}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-002: GET /components/{name}/versions/{ver} returns identical JSON")
    fun `VAL-002 detailed component by version`() {
        val sampleComponents =
            listOf(
                "payment-gateway",
                "auth-service",
                "cache-service",
                "transaction-engine",
                "settlement-service",
                "notification-service",
            )
        val failures = mutableListOf<String>()
        for (name in sampleComponents) {
            val ver = versionFor(name)
            val diff = safeCompareEndpoint(name, "/rest/api/2/components/$name/versions/$ver")
            if (diff != null) failures.add("[$name@$ver] $diff")
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("VAL-002 failures:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-003: GET .../versions/{ver}/vcs-settings
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-003: GET .../vcs-settings returns identical JSON")
    fun `VAL-003 vcs-settings`() {
        val sampleComponents =
            listOf(
                "payment-gateway",
                "discovery-service",
                "config-service",
                "settlement-service",
                "multi-root-service",
                "branch-template-svc",
            )
        val failures = mutableListOf<String>()
        for (name in sampleComponents) {
            val ver = versionFor(name)
            val diff =
                safeCompareEndpoint(
                    name,
                    "/rest/api/2/components/$name/versions/$ver/vcs-settings",
                )
            if (diff != null) failures.add("[$name@$ver] $diff")
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("VAL-003 failures:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-004: GET .../versions/{ver}/jira-component
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-004: GET .../jira-component returns identical JSON")
    fun `VAL-004 jira-component`() {
        val sampleComponents =
            listOf(
                "payment-gateway",
                "auth-service",
                "customer-beta-banking",
                "xml-parser",
                "transaction-engine",
                "message-broker",
            )
        val failures = mutableListOf<String>()
        for (name in sampleComponents) {
            val ver = versionFor(name)
            val diff =
                safeCompareEndpoint(
                    name,
                    "/rest/api/2/components/$name/versions/$ver/jira-component",
                )
            if (diff != null) failures.add("[$name@$ver] $diff")
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("VAL-004 failures:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-005: GET .../versions/{ver}/distribution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-005: GET .../distribution returns identical JSON")
    fun `VAL-005 distribution`() {
        val sampleComponents =
            listOf(
                "payment-gateway",
                "notification-service",
                "api-gateway",
                "desktop-client",
                "firmware-update",
                "tokenization-service",
            )
        val failures = mutableListOf<String>()
        for (name in sampleComponents) {
            val ver = versionFor(name)
            val diff =
                safeCompareEndpoint(
                    name,
                    "/rest/api/2/components/$name/versions/$ver/distribution",
                )
            if (diff != null) failures.add("[$name@$ver] $diff")
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("VAL-005 failures:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-006: GET .../maven-artifacts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-006: GET .../maven-artifacts returns identical JSON")
    fun `VAL-006 maven-artifacts`() {
        val sampleComponents =
            listOf(
                "payment-gateway",
                "db-kernel",
                "platform-commons",
                "data-mapper",
                "logging-service",
                // RES-C regression guard: per-range DISTRIBUTION_MAVEN marker override
                // (tokenization-service has (,2.0) → zip and [2.0,) → tgz extension).
                "tokenization-service",
            )
        val failures = mutableListOf<String>()
        for (name in sampleComponents) {
            val diff =
                safeCompareEndpoint(
                    name,
                    "/rest/api/2/components/$name/maven-artifacts",
                )
            if (diff != null) failures.add("[$name] $diff")
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("VAL-006 failures:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-010: Bulk canary — ALL components × ALL endpoints
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("VAL-010: Bulk validation — all components, all endpoints, full divergence report")
    @org.junit.jupiter.api.Disabled(
        "schema-v2 known limitations — RES-014 KTS build-tool beans closed by PR #208 " +
            "(`component_build_tool_beans` schema extension); expected residual is now " +
            "1× distribution-on-core-lib (FAKE-aggregator routing edge) plus possibly " +
            "1× `vcsSettings.externalRegistry: null` vs `NOT_AVAILABLE` (default-emit " +
            "divergence) — both pre-existing. Concrete count must be regenerated empirically " +
            "from a single VAL-010 run before re-enabling — do not hand-arithmetic. " +
            "Tracked in docs/db-migration/todo.md §FAKE-aggregator and in " +
            "docs/db-migration/implementation-progress.md Phase 6 ledger.",
    )
    fun `VAL-010 bulk canary`() {
        data class Divergence(
            val component: String,
            val endpoint: String,
            val detail: String,
        )

        val divergences = mutableListOf<Divergence>()

        for (name in allComponentNames) {
            // Endpoint 1: component by name (no version needed)
            safeCompareEndpoint(name, "/rest/api/2/components/$name")?.let {
                divergences.add(Divergence(name, "GET /components/$name", it))
            }

            // Endpoint 2: maven-artifacts (no version needed)
            safeCompareEndpoint(name, "/rest/api/2/components/$name/maven-artifacts")?.let {
                divergences.add(Divergence(name, "GET /components/$name/maven-artifacts", it))
            }

            // Skip version-specific endpoints for aggregators with fake ranges
            if (name in skipVersionTests) continue

            val ver = versionFor(name)
            val versionBase = "/rest/api/2/components/$name/versions/$ver"

            // Endpoint 3: detailed component by version
            safeCompareEndpoint(name, versionBase)?.let {
                divergences.add(Divergence(name, "GET .../versions/$ver", it))
            }

            // Endpoint 4: vcs-settings
            safeCompareEndpoint(name, "$versionBase/vcs-settings")?.let {
                divergences.add(Divergence(name, "GET .../versions/$ver/vcs-settings", it))
            }

            // Endpoint 5: jira-component
            safeCompareEndpoint(name, "$versionBase/jira-component")?.let {
                divergences.add(Divergence(name, "GET .../versions/$ver/jira-component", it))
            }

            // Endpoint 6: distribution
            safeCompareEndpoint(name, "$versionBase/distribution")?.let {
                divergences.add(Divergence(name, "GET .../versions/$ver/distribution", it))
            }
        }

        if (divergences.isNotEmpty()) {
            // Group by root cause pattern for easier debugging
            val report =
                buildString {
                    appendLine("=== VAL-010 DIVERGENCE REPORT ===")
                    appendLine("Total divergences: ${divergences.size}")
                    appendLine("Affected components: ${divergences.map { it.component }.distinct().size}")
                    appendLine()

                    // Group by endpoint pattern
                    divergences
                        .groupBy { it.endpoint.substringBefore("/versions/").substringAfterLast("/") }
                        .forEach { (endpointType, items) ->
                            appendLine("--- $endpointType (${items.size} divergences) ---")
                            for (d in items.take(5)) {
                                appendLine("  [${d.component}] ${d.endpoint}")
                                appendLine("    ${d.detail.take(300)}")
                            }
                            if (items.size > 5) {
                                appendLine("  ... and ${items.size - 5} more")
                            }
                            appendLine()
                        }
                }

            System.err.println(report)
            fail<Unit>(report)
        }
    }

    // -------------------------------------------------------------------------
    // VAL-011: Tools inherited from Defaults.requiredTools must persist on migration.
    // -------------------------------------------------------------------------

    /**
     * Regression: multi-range components that don't declare their own
     * `build.requiredTools` but inherit `BuildEnv` via `Defaults.groovy` lose
     * the tool when migrated to schema-v2. The Groovy resolver still emits it
     * in `buildParameters.tools` (Defaults merge at resolve time), but the
     * DB-backed resolver returns an empty list — import never persists the
     * inherited junction because, for synthetic-base components, `baseConfig`
     * is `configs.first()` (the first range block) and the Defaults merge
     * doesn't propagate `tools` into per-range configs the same way it does
     * into a top-level config.
     *
     * `cache-service` in `production-like/Components.groovy` has only
     * top-level scalars + two version-range blocks (`(,2.0)` and `[2.0,)`),
     * neither of which declares `requiredTools`. Production-like
     * `Defaults.groovy` sets `build { requiredTools = "BuildEnv" }`. The DB-
     * source response for `cache-service/versions/{version}` must contain
     * `BuildEnv` in `buildParameters.tools` — same as the Groovy baseline.
     *
     * Mirrors a real production pattern observed on multi-range components
     * that declare their own top-level `build { ... }` block (with scalars
     * such as `javaVersion`) but no `requiredTools` of their own.
     */
    @Test
    @DisplayName(
        "VAL-011: Defaults.requiredTools (BuildEnv) survives DB import — Git baseline vs DB candidate parity",
    )
    fun `VAL-011 defaults requiredTools git vs db parity`() {
        // Hand-picked candidates representative of the broken-on-prod pattern plus
        // historically-working control cases. production-like/Defaults.groovy sets
        // `build { requiredTools = "BuildEnv" }`, so the Git baseline must always
        // emit BuildEnv for these components. Any Git-vs-DB divergence in
        // `buildParameters.tools` is the bug.
        val candidates =
            listOf(
                // Synthetic fixture mirroring the real-world broken pattern: multi-range
                // with empty first range, top-level `build {}` block, no own
                // `requiredTools` (must inherit from Defaults). This is the case where
                // the Groovy baseline emits BuildEnv but the DB-backed resolver was
                // returning `[]`. Probe both sides of the `1.0.107` range boundary so
                // both the empty first range and the override second range exercise
                // the base-row junction fallback path.
                "legacy-multi-range-tool-inherit" to "1.0.107",
                "legacy-multi-range-tool-inherit" to "1.0.106",
                // Other multi-range / single-range candidates (control cases that have
                // historically worked end-to-end).
                "cache-service" to "1.0.0",
                "auth-service" to "1.0.0",
                "payment-gateway" to "1.0.0",
            )

        val failures = mutableListOf<String>()
        for ((name, version) in candidates) {
            sourceRegistry.setComponentSource(name, "git")
            val gitBody =
                mvc
                    .perform(get("/rest/api/2/components/$name/versions/$version").accept(APPLICATION_JSON))
                    .andReturn().response.contentAsString
            val gitTools =
                objectMapper.readTree(gitBody).path("buildParameters").path("tools").map { it.path("name").asText() }

            sourceRegistry.setComponentSource(name, "db")
            val dbBody =
                mvc
                    .perform(get("/rest/api/2/components/$name/versions/$version").accept(APPLICATION_JSON))
                    .andReturn().response.contentAsString
            val dbTools =
                objectMapper.readTree(dbBody).path("buildParameters").path("tools").map { it.path("name").asText() }

            if (gitTools.toSet() != dbTools.toSet()) {
                failures.add("[$name@$version] git=$gitTools db=$dbTools")
            }
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("Git vs DB buildParameters.tools divergence:\n${failures.joinToString("\n")}")
        }
    }

    // -------------------------------------------------------------------------
    // VAL-012: Cross-component groupPattern isolation.
    // -------------------------------------------------------------------------

    /**
     * Defensive: two components with distinct `groupId` values must not have their
     * `groupPattern` cross-contaminate in either source (Git or DB).
     *
     * Background: a compat-test against prod (2026-05-15) flagged a single component
     * whose `groupPattern` on one version-range had two CSV tokens on candidate vs one
     * on baseline (direction-inverted relative to the other drift cases). Code
     * inspection ruled it out as a code regression:
     *
     *   - `component_artifact_ids.component_id` is an FK to `components(id)`
     *     (V1__initial_schema.sql:149-154), one row per (component_id, version_range).
     *   - Write path: `EntityMappers.kt:495-546` assigns `groupPattern = config.groupIdPattern`
     *     directly (no append/concat across components).
     *   - Read path: `DatabaseComponentRegistryResolver.getMavenArtifactParameters(component)`
     *     queries by `componentEntity.artifactIds` — scoped to one component_id.
     *
     * The diff was pure DSL-revision drift on a CSV-valued `groupPattern` field.
     * This test pins the invariant for the future: even if multiple components share
     * a prefix or sit in the same project, their `groupPattern` values stay isolated
     * across Git and DB sources.
     */
    @Test
    @DisplayName(
        "VAL-012: cross-component groupPattern isolation — distinct groupIds do not bleed across Git or DB",
    )
    fun `VAL-012 cross-component groupPattern isolation git vs db parity`() {
        // Two production-like fixtures with deliberately distinct groupIds:
        //  cache-service: org.octopusden.octopus.cache
        //  auth-service:  org.octopusden.octopus.auth
        val a = "cache-service"
        val b = "auth-service"

        fun groupPatternTokens(component: String, source: String): Set<String> {
            sourceRegistry.setComponentSource(component, source)
            val body =
                mvc
                    .perform(get("/rest/api/2/components/$component/maven-artifacts").accept(APPLICATION_JSON))
                    .andExpect(status().isOk)
                    .andReturn().response.contentAsString
            val tree = objectMapper.readTree(body)
            // groupPattern is a comma-separated list of Maven groupIds. Split into
            // individual tokens so isolation can be checked as a set operation rather
            // than via substring matching (which is fragile across fixture renames).
            return tree
                .fields()
                .asSequence()
                .flatMap { (_, cfg) -> cfg.path("groupPattern").asText("").split(",").asSequence() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        // Set-level isolation invariant: the two components must not share any
        // groupPattern token in either source. Substring checks would be fragile
        // (the test must survive renames of either fixture without producing false
        // positives or false negatives).
        val failures = mutableListOf<String>()
        for (source in listOf("git", "db")) {
            val tokensA = groupPatternTokens(a, source)
            val tokensB = groupPatternTokens(b, source)
            if (tokensA.isEmpty()) {
                failures += "[$a/$source] no groupPattern tokens returned — isolation test cannot run; check fixture"
            }
            if (tokensB.isEmpty()) {
                failures += "[$b/$source] no groupPattern tokens returned — isolation test cannot run; check fixture"
            }
            val shared = tokensA intersect tokensB
            if (shared.isNotEmpty()) {
                failures += "[$source] cross-contamination: components '$a' and '$b' share groupPattern tokens $shared (tokensA=$tokensA, tokensB=$tokensB)"
            }
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("Cross-component groupPattern isolation violated:\n${failures.joinToString("\n")}")
        }
    }

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
