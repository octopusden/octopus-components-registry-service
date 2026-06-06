package org.octopusden.octopus.components.registry.server

import com.fasterxml.jackson.core.type.TypeReference
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionRangeDTO
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
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
import org.springframework.transaction.annotation.Transactional
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

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

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

    /**
     * Import-path pin: after auto-migrate, per-range jira.displayName scalars must
     * land on the correct configuration rows (BASE null-clear + SCALAR_OVERRIDE string).
     */
    @Test
    @DisplayName("MIG-045-006: import persists per-range jira.displayName rows for synthetic-base fixture")
    fun testMIG045006_importPersistsPerRangeJiraDisplayNameScalars_dbMode() {
        val component = componentRepository.findByComponentKey(MIG045_FIXTURE)
        assertNotNull(component, "fixture component must be auto-migrated")

        val rows = configurationRepository.findByComponentId(component!!.id!!)
        val baseRow = rows.single { it.rowType == "BASE" }
        assertEquals("[1.0,2.0)", baseRow.versionRange)
        assertNull(
            baseRow.jiraDisplayName,
            "BASE row for [1.0,2.0) must store explicit jira.displayName=null from DSL",
        )

        val overrideRow =
            configurationRepository.findByComponentIdAndVersionRangeAndOverriddenAttribute(
                component.id!!,
                "[2.0,)",
                "jira.displayName",
            )
        assertNotNull(overrideRow, "import must emit jira.displayName SCALAR_OVERRIDE for [2.0,)")
        assertEquals("Range Override Display", overrideRow!!.jiraDisplayName)
    }

    /**
     * Full-stack pin for CARDS-like fallback bug: components.jira_display_name is
     * populated while the synthetic BASE range carries explicit null. Both resolver
     * and HTTP must return null for [1.0,2.0), not bleed the component default.
     */
    @Test
    @Transactional
    @DisplayName(
        "MIG-045-007: null-clear on synthetic BASE range survives populated components.jira_display_name (DB + HTTP)",
    )
    fun testMIG045007_nullClearSurvivesComponentLevelDefault_dbMode() {
        val component = componentRepository.findByComponentKey(MIG045_FIXTURE)!!
        component.jiraDisplayName = "Component Default Display"
        componentRepository.saveAndFlush(component)

        val baseRow =
            configurationRepository.findByComponentId(component.id!!)
                .single { it.rowType == "BASE" && it.versionRange == "[1.0,2.0)" }
        assertNull(baseRow.jiraDisplayName, "precondition: BASE row must keep explicit null")

        val resolverRanges =
            getJiraComponentVersionRangesByProject(MIG045_PROJECT)
                .filter { it.componentName == MIG045_FIXTURE }
        assertNull(
            resolverRanges.first { it.versionRange == "[1.0,2.0)" }.component.displayName,
            "resolver must not fall back to components.jira_display_name on synthetic BASE range",
        )
        assertEquals(
            "Range Override Display",
            resolverRanges.first { it.versionRange == "[2.0,)" }.component.displayName,
        )

        val httpRanges = fetchProjectJiraComponentVersionRanges(MIG045_PROJECT)
            .filter { it.componentName == MIG045_FIXTURE }
        assertNull(
            httpRanges.first { it.versionRange == "[1.0,2.0)" }.component.displayName,
            "HTTP must not fall back to components.jira_display_name on synthetic BASE range",
        )
        assertEquals(
            "Range Override Display",
            httpRanges.first { it.versionRange == "[2.0,)" }.component.displayName,
        )
    }

    /**
     * End-to-end HTTP pin for MIG-045: strict JSON parse (not substring matching).
     */
    @Test
    @DisplayName(
        "MIG-045-008: GET /projects/{projectKey}/jira-component-version-ranges surfaces per-range jira.displayName (DB resolver)",
    )
    fun testMIG045008_perRangeJiraDisplayNameOnProjectJiraComponentVersionRanges_dbMode() {
        val ranges =
            fetchProjectJiraComponentVersionRanges(MIG045_PROJECT)
                .filter { it.componentName == MIG045_FIXTURE }

        assertNull(ranges.first { it.versionRange == "[1.0,2.0)" }.component.displayName)
        assertEquals(
            "Range Override Display",
            ranges.first { it.versionRange == "[2.0,)" }.component.displayName,
        )
    }

    private fun fetchProjectJiraComponentVersionRanges(projectKey: String): List<JiraComponentVersionRangeDTO> {
        val response =
            mvc
                .perform(
                    get("/rest/api/2/projects/$projectKey/jira-component-version-ranges")
                        .accept(APPLICATION_JSON),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readValue(
            response,
            object : TypeReference<List<JiraComponentVersionRangeDTO>>() {},
        )
    }

    companion object {
        private const val MIG045_FIXTURE = "TEST_PER_RANGE_JIRA_DISPLAY_NAME"
        private const val MIG045_PROJECT = "PRDN"

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
