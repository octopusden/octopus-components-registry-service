package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Paths

/**
 * Runs ALL 25 resolver-behavior tests (RES-001..RES-025) from
 * [BaseComponentsRegistryServiceTest] against the **DB backend** after migration.
 *
 * Mirrors [ComponentsRegistryServiceControllerTest] which runs the same tests
 * against the Git backend.
 *
 * @see docs/registry/requirements-resolver.md
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "test-db")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class DbBackedComponentsRegistryServiceControllerTest : MockMvcRegistryTestSupport() {
    @Autowired
    private lateinit var sourceRegistry: ComponentSourceRegistry

    /**
     * Post ui-swift-sloth-system-single: DB-source resolver stores TESTONE's
     * system as the scalar `components.system_code = "CLASSIC"` (first
     * non-blank entry from the `"CLASSIC,ALFA"` DSL CSV), so the V1 detail
     * surface emits a single-entry set. Overrides the shared base default
     * (which still reflects the git-source full-set shape).
     */
    override val expectedTestoneSystemSet: Set<String> = setOf("CLASSIC")

    init {
        val testResourcesUri =
            DbBackedComponentsRegistryServiceControllerTest::class.java
                .getResource("/expected-data")!!
                .toURI()
        val testResourcesPath = Paths.get(testResourcesUri).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeAll
    fun migrateAllToDb() {
        // Step 1: migrate defaults
        mvc
            .perform(post("/rest/api/4/admin/migrate-defaults").with(adminJwt()).accept(APPLICATION_JSON))
            .andExpect(status().isOk)

        // Step 2: migrate all components
        val resultJson =
            mvc
                .perform(
                    post("/rest/api/4/admin/migrate-components").with(adminJwt()).accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val migrationResult = objectMapper.readTree(resultJson)
        assertTrue(
            migrationResult.path("migrated").asInt() > 0,
            "Expected components to be migrated, got: $migrationResult",
        )
        assertEquals(
            0,
            migrationResult.path("failed").asInt(),
            "Expected no migration failures: ${migrationResult.path("results").filter { !it.path("success").asBoolean() }}",
        )

        // Step 3: discover all component names and switch to DB source
        val componentsJson =
            mvc
                .perform(
                    get("/rest/api/2/components").accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        val tree = objectMapper.readTree(componentsJson)
        val componentNames =
            tree
                .path("components")
                .map { it.path("id").asText() }
                .filter { it.isNotEmpty() }

        for (name in componentNames) {
            sourceRegistry.setComponentSource(name, "db")
        }
    }

    override fun testGetBuildTools() = super.testGetBuildTools()

    // ADR-018 decoupled redesign: the DB-backed resolver enumerates the partition of merged coverage
    // by value-change edges, so adjacent ranges that resolve identically collapse (e.g.
    // TEST_COMPONENT2_WITH_SEVERAL_BRANCHES's two identical `v2` blocks → one (,03.38.31]; and
    // TEST_COMPONENT_WITH_DOC_AND_VERSIONS's two equal-resolving blocks → one [1.0,)). It also
    // re-renders ranges canonically (no internal whitespace). The DSL-loader base test keeps its
    // verbatim-block fixture; this one points at the decoupled expectation.
    override val jiraComponentVersionRangesFixture: String =
        "expected-data/jira-component-version-ranges-decoupled.json"

    /**
     * Regression: components migrated from a DSL with no `vcsSettings` block at all
     * (no VCS roots and no externalRegistry) resolve to `EscrowModuleConfig.vcsSettings == null`.
     * `BaseComponentController.getAllComponents` used to dereference
     * `config.vcsSettings.versionControlSystemRoots` unconditionally inside the
     * `vcs-path` filter lambda, NPE-ing the whole request to a 500 whenever the
     * query parameter was present and at least one component had a null
     * `vcsSettings`. After the fix the request must succeed and just filter
     * those components out.
     */
    @Test
    @DisplayName(
        "GET /rest/api/2/components?vcs-path=<any> returns 200 (no NPE on null vcsSettings)",
    )
    fun getAllComponents_vcsPathFilter_doesNotNpeOnNullVcsSettings() {
        mvc
            .perform(
                get("/rest/api/2/components")
                    .param("vcs-path", "ssh://hg@mercurial/__no_such_component__")
                    .accept(APPLICATION_JSON),
            ).andExpect(status().isOk)
    }

    /**
     * Regression pin for the per-range `hotfixVersionFormat` override
     * propagation chain (task #16) on the DB-backed resolver path.
     *
     * The fixture component `TEST_PER_RANGE_HOTFIX_FORMAT` declares a
     * per-component (base) `hotfixVersionFormat = '$major.$minor.$service-$fix'`
     * and an override on version range `[2.0,)`:
     *   `hotfixVersionFormat = '$major.$minor.$service-$fix-$build'`.
     *
     * For a version IN the override range (e.g. `2.0.0-1234`) the DB-backed
     * resolver must surface the per-range value. v3 currently surfaces the
     * per-component base value instead, because
     * `ImportServiceImpl.populateScalarsFromConfig` explicitly skips writing
     * `hotfixVersionFormat` to the per-range row, and
     * `EntityMappers.buildJiraComponent` reads only the per-component scalar.
     *
     * The Git-backed resolver (`Mappers.kt` + `ComponentRegistryResolverImpl`)
     * surfaces the per-range value correctly via the upstream Groovy loader,
     * which is why the same UT passes in `ComponentsRegistryServiceControllerTest`
     * (Git-mode profile). The DB-backed path is the one that diverges from
     * v2.0.87 prod (= `f1-gateway` baseline); this test pins parity.
     */
    @Test
    @DisplayName(
        "GET /jira-component surfaces per-range hotfixVersionFormat override (DB resolver)",
    )
    fun testPerRangeHotfixVersionFormatOverridesBase_dbMode() {
        val response =
            mvc
                .perform(
                    get("/rest/api/2/components/TEST_PER_RANGE_HOTFIX_FORMAT/versions/2.0.0-1234/jira-component")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val expected = "\$major.\$minor.\$service-\$fix-\$build"
        assertTrue(
            response.contains("\"hotfixVersionFormat\":\"$expected\""),
            "expected jira-component for version 2.0.0-1234 (in range [2.0,)) to carry " +
                "the per-range hotfixVersionFormat='$expected'; got: ${response.take(800)}",
        )
    }

    /**
     * Boundary companion to `testPerRangeHotfixVersionFormatOverridesBase_dbMode`:
     * a version OUTSIDE the override range must fall back to the per-component
     * (base) value on the DB-backed resolver. Pins that the fix does not
     * regress the inheritance path for ranges without an explicit per-range
     * override.
     */
    @Test
    @DisplayName(
        "GET /jira-component for version outside override range falls back to base hotfixVersionFormat (DB resolver)",
    )
    fun testVersionOutsidePerRangeHotfixOverrideFallsBackToBase_dbMode() {
        val response =
            mvc
                .perform(
                    get("/rest/api/2/components/TEST_PER_RANGE_HOTFIX_FORMAT/versions/1.5.0/jira-component")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val expected = "\$major.\$minor.\$service-\$fix"
        assertTrue(
            response.contains("\"hotfixVersionFormat\":\"$expected\""),
            "expected jira-component for version 1.5.0 (outside range [2.0,), in [1.0,2.0)) to carry " +
                "the per-component base hotfixVersionFormat='$expected'; got: ${response.take(800)}",
        )
    }

    companion object {
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
