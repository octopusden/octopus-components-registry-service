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
