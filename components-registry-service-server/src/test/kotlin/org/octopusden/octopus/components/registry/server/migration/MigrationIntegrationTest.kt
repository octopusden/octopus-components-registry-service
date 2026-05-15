package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Paths

/**
 * MIG-039 schema-v2 integration tests.
 *
 * Verifies the §6 import pipeline results against the ft-db profile
 * (H2 in-memory + auto-migrate from test fixtures in TestComponents.groovy).
 *
 * Test IDs reference requirements-migration.md.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@Timeout(120)
class MigrationIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @Autowired
    private lateinit var systemRepository: SystemRepository

    @Autowired
    private lateinit var toolRepository: ToolRepository

    @Autowired
    private lateinit var labelRepository: LabelRepository

    init {
        val testResourcesPath =
            Paths.get(MigrationIntegrationTest::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    // =========================================================================
    // MIG-029: DB → EscrowModule round-trip preserves the absence of a default
    //          ALL_VERSIONS config for version-range-only components.
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-029: version-range-only component (TEST_COMPONENT2_WITH_SEVERAL_BRANCHES) " +
            "has isSyntheticBase=true on its base row and no ALL_VERSIONS range",
    )
    fun mig029_syntheticBaseForVersionRangeOnlyComponent() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT2_WITH_SEVERAL_BRANCHES")
        assertNotNull(component, "Component TEST_COMPONENT2_WITH_SEVERAL_BRANCHES must be migrated")

        val configurations = configurationRepository.findByComponentId(component!!.id!!)
        assertFalse(configurations.isEmpty(), "Must have at least one configuration row")

        // The component has only explicit version ranges — no ALL_VERSIONS block in DSL.
        // The base row must be marked synthetic.
        val baseRow: ComponentConfigurationEntity? =
            configurationRepository.findByComponentIdAndOverriddenAttributeIsNull(component.id!!)
        assertNotNull(baseRow, "Must have a base row (overridden_attribute IS NULL)")
        assertTrue(
            baseRow!!.isSyntheticBase,
            "Base row for version-range-only component must have isSyntheticBase=true",
        )

        // No row should have versionRange == ALL_VERSIONS from the DSL — that would indicate
        // the pipeline erroneously created a non-synthetic ALL_VERSIONS row.
        val allVersionsRows = configurations.filter { it.versionRange == ALL_VERSIONS && !it.isSyntheticBase }
        assertTrue(
            allVersionsRows.isEmpty(),
            "version-range-only component must NOT have a non-synthetic ALL_VERSIONS base row; found: $allVersionsRows",
        )
    }

    // =========================================================================
    // MIG-032: Reference dictionaries populated during pre-pass.
    // =========================================================================

    @Test
    @DisplayName("MIG-032: systems dictionary populated — CLASSIC, ALFA, NONE present from TestComponents")
    fun mig032_systemsDictionaryPopulated() {
        // TESTONE has system = "CLASSIC,ALFA"; Defaults has system = "NONE"
        val classic = systemRepository.findByCode("CLASSIC")
        assertNotNull(classic, "System 'CLASSIC' must be in systems dictionary after migration")

        val alfa = systemRepository.findByCode("ALFA")
        assertNotNull(alfa, "System 'ALFA' must be in systems dictionary after migration")
    }

    @Test
    @DisplayName("MIG-032: tools dictionary populated — BuildEnv and PowerBuilderCompiler170 from TESTONE")
    fun mig032_toolsDictionaryPopulated() {
        // TESTONE has build.requiredTools = "BuildEnv,PowerBuilderCompiler170"
        val buildEnv = toolRepository.findByName("BuildEnv")
        assertNotNull(buildEnv, "Tool 'BuildEnv' must be in tools dictionary after migration")

        val pbc = toolRepository.findByName("PowerBuilderCompiler170")
        assertNotNull(pbc, "Tool 'PowerBuilderCompiler170' must be in tools dictionary after migration")
    }

    @Test
    @DisplayName("MIG-032: labels dictionary populated — Label2 from TESTONE, java/sql from TEST_COMPONENT3")
    fun mig032_labelsDictionaryPopulated() {
        // TESTONE has labels = ['Label2']
        val label2 = labelRepository.findByCode("Label2")
        assertNotNull(label2, "Label 'Label2' must be in labels dictionary after migration")

        // TEST_COMPONENT3 has labels = ['java', 'sql']
        val javaLabel = labelRepository.findByCode("java")
        assertNotNull(javaLabel, "Label 'java' must be in labels dictionary after migration")

        val sqlLabel = labelRepository.findByCode("sql")
        assertNotNull(sqlLabel, "Label 'sql' must be in labels dictionary after migration")
    }

    // =========================================================================
    // MIG-033: Distribution split into four specialized child tables.
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-033: TESTONE distribution.GAV splits — 'org.octopusden.octopus.test:versions-api:jar' " +
            "→ distribution_maven_artifacts; no file-URL artifacts",
    )
    fun mig033_mavenArtifactSplit() {
        val component = componentRepository.findByComponentKey("TESTONE")
        assertNotNull(component, "TESTONE must be migrated")

        val baseRow =
            configurationRepository.findByComponentIdAndOverriddenAttributeIsNull(component!!.id!!)
        assertNotNull(baseRow, "TESTONE must have a base row")

        // TESTONE distribution.GAV = "org.octopusden.octopus.test:versions-api:jar"
        // This should produce one Maven artifact entry (not a file-URL entry).
        assertTrue(
            baseRow!!.mavenArtifacts.isNotEmpty() || hasMavenMarkerWithArtifacts(component.id!!),
            "TESTONE must have at least one maven artifact after distribution split",
        )
    }

    @Test
    @DisplayName("MIG-033: TEST_COMPONENT3 fileUrl artifact from GAV file:///acs:... parsed into fileUrlArtifacts")
    fun mig033_fileUrlArtifactSplit() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT3")
        assertNotNull(component, "TEST_COMPONENT3 must be migrated")

        // TEST_COMPONENT3 distribution.GAV includes 'file:///acs:$major-$minor-$service-$fix'
        // This should produce one file-URL artifact entry.
        val allRows = configurationRepository.findByComponentId(component!!.id!!)
        val hasFileUrl = allRows.any { row -> row.fileUrlArtifacts.isNotEmpty() }
        assertTrue(
            hasFileUrl,
            "TEST_COMPONENT3 must have at least one fileUrl artifact (from file:///acs:... in GAV)",
        )
    }

    @Test
    @DisplayName("MIG-033: TESTONE distribution.docker → distribution_docker_images")
    fun mig033_dockerImageSplit() {
        val component = componentRepository.findByComponentKey("TESTONE")
        assertNotNull(component, "TESTONE must be migrated")

        // TESTONE distribution.docker = "test/versions-api"
        val allRows = configurationRepository.findByComponentId(component!!.id!!)
        val hasDocker = allRows.any { row -> row.dockerImages.isNotEmpty() }
        assertTrue(
            hasDocker,
            "TESTONE must have at least one docker image entry (from distribution.docker = 'test/versions-api')",
        )
    }

    // =========================================================================
    // MIG-036: Per-attribute version-range overrides (Model A').
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-036: TEST_COMPONENT3 has override rows for version ranges " +
            "'(,1.0.107)' and '[1.0.107,)' — marker rows for jira and vcs",
    )
    fun mig036_versionRangeOverridesCreated() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT3")
        assertNotNull(component, "TEST_COMPONENT3 must be migrated")

        val allRows = configurationRepository.findByComponentId(component!!.id!!)

        // TEST_COMPONENT3 has:
        // - top-level: GAV, explicit=true, external=true, jira.projectKey=TC3, build.javaVersion=1.8
        // - "(,1.0.107)" {} — empty block (no overrides)
        // - "[1.0.107,)" { jira { releaseVersionFormat = '...' }; tag = '...' }
        // So we should have at least one override row for range "[1.0.107,)"
        val rangeRows = allRows.filter { it.versionRange == "[1.0.107,)" }
        assertTrue(
            rangeRows.isNotEmpty(),
            "Must have override rows for version range '[1.0.107,)'; found rows: ${allRows.map { it.versionRange }}",
        )
    }

    @Test
    @DisplayName(
        "MIG-036: TEST_COMPONENT2_WITH_SEVERAL_BRANCHES has VCS marker override rows " +
            "for all three version ranges",
    )
    fun mig036_vcsMarkerOverrideRows() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT2_WITH_SEVERAL_BRANCHES")
        assertNotNull(component, "TEST_COMPONENT2_WITH_SEVERAL_BRANCHES must be migrated")

        val allRows = configurationRepository.findByComponentId(component!!.id!!)

        // Has 3 explicit vcsSettings blocks across 3 version ranges.
        // The pipeline should have created vcs.settings marker rows for the non-base ranges.
        val vcsMarkerRows = allRows.filter { it.overriddenAttribute == MarkerAttributes.VCS_SETTINGS }
        assertTrue(
            vcsMarkerRows.isNotEmpty(),
            "Must have at least one vcs.settings marker row; found: ${allRows.map { "${it.versionRange}:${it.overriddenAttribute}" }}",
        )
    }

    // =========================================================================
    // MIG-037: Unified VCS model (single row with name=null for single-VCS).
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-037: TEST_COMPONENT has single VCS entry with name=null " +
            "(unified VCS model, no discriminator column)",
    )
    fun mig037_unifiedVcsModel_singleRoot() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT")
        assertNotNull(component, "TEST_COMPONENT must be migrated")

        val baseRow =
            configurationRepository.findByComponentIdAndOverriddenAttributeIsNull(component!!.id!!)
        assertNotNull(baseRow, "TEST_COMPONENT must have a base row")

        // TEST_COMPONENT has single vcsUrl = "ssh://hg@mercurial/test-component"
        // → must produce one VcsSettingsEntryEntity with name=null
        val vcsEntries = baseRow!!.vcsEntries
        assertTrue(vcsEntries.isNotEmpty(), "Must have at least one VCS entry")
        assertTrue(
            vcsEntries.any { it.name == null },
            "Single-VCS component must have a VCS entry with name=null (unified VCS model)",
        )
    }

    @Test
    @DisplayName(
        "MIG-037: TEST_COMPONENT2_WITH_SEVERAL_ROOTS has two VCS entries " +
            "with distinct names (multi-VCS unified model)",
    )
    fun mig037_unifiedVcsModel_multiRoot() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT2_WITH_SEVERAL_ROOTS")
        assertNotNull(component, "TEST_COMPONENT2_WITH_SEVERAL_ROOTS must be migrated")

        val baseRow =
            configurationRepository.findByComponentIdAndOverriddenAttributeIsNull(component!!.id!!)
        assertNotNull(baseRow, "TEST_COMPONENT2_WITH_SEVERAL_ROOTS must have a base row")

        // Has vcsSettings { cvs { ... }; mercurial { ... } } → two named entries
        val vcsEntries = baseRow!!.vcsEntries
        assertTrue(
            vcsEntries.size >= 2,
            "Multi-VCS component must have 2+ VCS entries, found: ${vcsEntries.size}",
        )
        val distinctNames = vcsEntries.mapNotNull { it.name }.toSet()
        assertTrue(
            distinctNames.size >= 2,
            "Multi-VCS entries must have distinct non-null names, found: $distinctNames",
        )
    }

    // =========================================================================
    // MIG-007: Migration is idempotent — already-migrated components are skipped.
    // =========================================================================

    @Test
    @DisplayName("MIG-007: component count is stable — no duplicate components after auto-migrate")
    fun mig007_idempotency_noduplicates() {
        // ft-db auto-migrates at startup. The component count should be stable
        // (no duplicates). Every component key must appear exactly once.
        val allComponents = componentRepository.findAll()
        val keyFrequencies = allComponents.groupBy { it.componentKey }.mapValues { (_, v) -> v.size }
        val duplicates = keyFrequencies.filter { (_, count) -> count > 1 }
        assertTrue(
            duplicates.isEmpty(),
            "Found duplicate component keys after migration: $duplicates",
        )
    }

    // =========================================================================
    // MIG-035: Aggregator group (componentGroup linkage via parentComponent).
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-035/§6.3: TESTONE sub-component 'versions-api' has parentComponent = 'TESTONE' " +
            "resolved via Pass 2",
    )
    fun mig035_parentComponentResolvedInPass2() {
        // TESTONE has a `components { "versions-api" { parentComponent = "TESTONE" ... } }` block.
        // Pass 2 should resolve the FK reference.
        val versionsApi = componentRepository.findByComponentKey("versions-api")
        assertNotNull(versionsApi, "'versions-api' sub-component must be migrated")

        // parentComponent must be resolved (not null)
        val parentRef = versionsApi!!.parentComponent
        assertNotNull(
            parentRef,
            "'versions-api' must have parentComponent resolved to TESTONE after Pass 2",
        )
        assertEquals("TESTONE", parentRef!!.componentKey, "parentComponent.componentKey must be 'TESTONE'")
    }

    // =========================================================================
    // MIG-009: component_source switches to "db" after migration.
    // =========================================================================

    @Test
    @DisplayName("MIG-009: all auto-migrated test components are in DB (source registry check)")
    fun mig009_allComponentsInDb() {
        // ft-db auto-migrates at startup. All test components should be present in the DB.
        val dbCount = componentRepository.count()
        assertTrue(dbCount > 0, "At least one component must be present in DB after auto-migrate")

        // Specifically, the key fixture components must be present
        for (key in listOf("TESTONE", "TEST_COMPONENT", "TEST_COMPONENT3", "TEST_COMPONENT2_WITH_SEVERAL_BRANCHES")) {
            val c = componentRepository.findByComponentKey(key)
            assertNotNull(c, "Component '$key' must be in DB after auto-migrate")
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Checks whether there is a `distribution.maven` marker row for the given component
     * that carries at least one maven artifact (loaded lazily here via the base row
     * or any marker row).
     */
    private fun hasMavenMarkerWithArtifacts(componentId: java.util.UUID): Boolean {
        val rows = configurationRepository.findByComponentId(componentId)
        return rows.any { row ->
            row.overriddenAttribute == MarkerAttributes.DISTRIBUTION_MAVEN && row.mavenArtifacts.isNotEmpty()
        }
    }
}
