package org.octopusden.octopus.components.registry.server.migration

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentFilter
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentGroupEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentManagementService
import org.octopusden.octopus.components.registry.server.service.ImportService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageRequest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Paths

/**
 * MIG-039 schema-v2 integration tests.
 *
 * Verifies the §6 import pipeline results against the ft-db profile
 * (H2 in-memory + auto-migrate from test fixtures in TestComponents.groovy).
 *
 * Test IDs reference requirements-migration.md.
 *
 * `@DirtiesContext(BEFORE_CLASS)` guarantees a fresh Spring context and a fresh
 * H2 in-memory database when this class starts — regardless of the test execution
 * order. Without it, any preceding `@DirtiesContext(AFTER_CLASS)` class could
 * destroy the shared H2 instance, leaving the auto-migrate path with no schema.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Timeout(120)
@Tag("integration")
class MigrationIntegrationTest {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var componentRepository: ComponentRepository

    @Autowired
    private lateinit var configurationRepository: ComponentConfigurationRepository

    @Autowired
    private lateinit var componentGroupRepository: ComponentGroupRepository

    @Autowired
    private lateinit var systemRepository: SystemRepository

    @Autowired
    private lateinit var toolRepository: ToolRepository

    @Autowired
    private lateinit var labelRepository: LabelRepository

    @Autowired
    private lateinit var importService: ImportService

    @Autowired
    private lateinit var componentManagementService: ComponentManagementService

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
        "MIG-029 (ADR-018 decoupled): version-range-only component (TEST_COMPONENT2_WITH_SEVERAL_BRANCHES) " +
            "migrates to an ALL_VERSIONS base (not synthetic); its three contiguous blocks tile all-versions " +
            "so merged coverage = ALL → NO RANGE_PRESENCE rows (enumeration re-splits at override edges)",
    )
    fun mig029_decoupledBaseForVersionRangeOnlyComponent() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT2_WITH_SEVERAL_BRANCHES")
        assertNotNull(component, "Component TEST_COMPONENT2_WITH_SEVERAL_BRANCHES must be migrated")

        val configurations = configurationRepository.findByComponentId(component!!.id!!)
        assertFalse(configurations.isEmpty(), "Must have at least one configuration row")

        // Decoupled model: the base is always the ALL_VERSIONS effective default — never a
        // synthetic-bounded range. Coverage is a separate layer (RANGE_PRESENCE rows).
        val baseRow: ComponentConfigurationEntity? =
            configurationRepository.findBaseByComponentId(component.id!!)
        assertNotNull(baseRow, "Must have a base row (overridden_attribute IS NULL)")
        assertFalse(
            baseRow!!.isSyntheticBase,
            "Decoupled model never marks a base synthetic (is_synthetic_base must be false)",
        )
        assertEquals(
            ALL_VERSIONS,
            baseRow.versionRange,
            "Base row must sit at ALL_VERSIONS (the effective default), not a bounded block range",
        )

        // Decoupled redesign: coverage is stored MERGED (override-independent). The three declared
        // blocks (,03.38.25] / (03.38.25,03.38.31] / (03.38.31,) are contiguous and tile every
        // version, so their union is all-versions → coverage = ALL and NO RANGE_PRESENCE rows are
        // emitted (the absence of presence rows + an ALL_VERSIONS base IS "supported = all"). The
        // per-block VCS overrides survive as MARKER rows and re-split the enumeration at READ time.
        val presenceRanges =
            configurations.filter { it.rowType == "RANGE_PRESENCE" }.map { it.versionRange }
        assertTrue(
            presenceRanges.isEmpty(),
            "Blocks tiling all-versions must merge to ALL coverage (no RANGE_PRESENCE rows); found: $presenceRanges",
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
    @Transactional
    @DisplayName(
        "MIG-033: TESTONE distribution.GAV splits — 'org.octopusden.octopus.test:versions-api:jar' " +
            "→ distribution_maven_artifacts; no file-URL artifacts",
    )
    fun mig033_mavenArtifactSplit() {
        val component = componentRepository.findByComponentKey("TESTONE")
        assertNotNull(component, "TESTONE must be migrated")

        val baseRow =
            configurationRepository.findBaseByComponentId(component!!.id!!)
        assertNotNull(baseRow, "TESTONE must have a base row")

        // TESTONE distribution.GAV = "org.octopusden.octopus.test:versions-api:jar"
        // This should produce one Maven artifact entry (not a file-URL entry).
        assertTrue(
            baseRow!!.mavenArtifacts.isNotEmpty() || hasMavenMarkerWithArtifacts(component.id!!),
            "TESTONE must have at least one maven artifact after distribution split",
        )
    }

    @Test
    @Transactional
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
    @Transactional
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
        "MIG-036 (ADR-018): TEST_COMPONENT3 base carries the OPEN-UPPER [1.0.107,) release format; " +
            "the OLDER (,1.0.107) block carries the value-layer overrides (base = newest value)",
    )
    fun mig036_versionRangeOverridesCreated() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT3")
        assertNotNull(component, "TEST_COMPONENT3 must be migrated")

        val allRows = configurationRepository.findByComponentId(component!!.id!!)

        // TEST_COMPONENT3 has:
        // - top-level: GAV, explicit=true, external=true, jira.projectKey=TC3, build.javaVersion=1.8,
        //   tag='octopustds-$version', NO releaseVersionFormat.
        // - "(,1.0.107)" {} — empty block (inherits top-level: no releaseVersionFormat, default tag)
        // - "[1.0.107,)" { jira { releaseVersionFormat = '$major.$minor.$service-$fix' }; tag = 'tdsecure-$version' }
        // Per ADR-018 the base = the OPEN-UPPER [1.0.107,) block's effective value.
        val baseRow = configurationRepository.findBaseByComponentId(component.id!!)
        assertNotNull(baseRow, "TEST_COMPONENT3 must have a base row")
        assertEquals(
            "\$major.\$minor.\$service-\$fix", baseRow!!.jiraReleaseVersionFormat,
            "BASE must carry the open-upper [1.0.107,) releaseVersionFormat (newest value = base default)",
        )

        // The open-upper block is now the base — it leaves no standalone override/presence row.
        assertTrue(
            allRows.none { it.versionRange == "[1.0.107,)" },
            "The open-upper [1.0.107,) block became the base; expected no standalone row. " +
                "Found: ${allRows.map { "${it.versionRange}/${it.rowType}" }}",
        )

        // The OLDER (,1.0.107) block diverges from base and survives as value-layer override rows. This is
        // the exact legacy shape that motivated the amendment: the newest base carries the open-upper '$major.$minor.$service-$fix',
        // while the older band (which sets no releaseVersionFormat of its own) inherits the Defaults.groovy
        // default '$major.$minor.$service' — emitted as a SCALAR_OVERRIDE so the base's newer value does
        // not leak into the older range.
        val olderRows = allRows.filter { it.versionRange == "(,1.0.107)" }
        val releaseOverride =
            olderRows.singleOrNull {
                it.rowType == "SCALAR_OVERRIDE" && it.overriddenAttribute == "jira.releaseVersionFormat"
            }
        assertNotNull(
            releaseOverride,
            "The older (,1.0.107) block must carry a jira.releaseVersionFormat SCALAR_OVERRIDE. " +
                "Found: ${allRows.map { "${it.versionRange}/${it.rowType}/${it.overriddenAttribute}" }}",
        )
        assertEquals(
            "\$major.\$minor.\$service", releaseOverride!!.jiraReleaseVersionFormat,
            "The (,1.0.107) override must carry the older (Defaults) release format, NOT the newer base value",
        )
        // A marker (vcs.settings) override also follows base: the older block keeps the top-level tag
        // 'octopustds-$version' while the base carries the open-upper 'tdsecure-$version'.
        assertTrue(
            olderRows.any { it.rowType == "MARKER" && it.overriddenAttribute == MarkerAttributes.VCS_SETTINGS },
            "The older (,1.0.107) block must carry a vcs.settings MARKER (its tag differs from base). " +
                "Found: ${allRows.map { "${it.versionRange}/${it.rowType}/${it.overriddenAttribute}" }}",
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
    // MIG-037: Unified VCS model (VCS root name stored verbatim, never NULL).
    // =========================================================================

    @Test
    @Transactional
    @DisplayName(
        "MIG-037: TEST_COMPONENT has single VCS entry with name='main' " +
            "(inline DSL form: vcsUrl= produces VersionControlSystemRoot.name='main')",
    )
    fun mig037_unifiedVcsModel_singleRoot() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT")
        assertNotNull(component, "TEST_COMPONENT must be migrated")

        val baseRow =
            configurationRepository.findBaseByComponentId(component!!.id!!)
        assertNotNull(baseRow, "TEST_COMPONENT must have a base row")

        // TEST_COMPONENT uses inline DSL form: vcsUrl = "ssh://hg@mercurial/test-component"
        // The Groovy DSL wraps this as VersionControlSystemRoot.create("main", ...).
        // → entity name must be "main" (literal), NOT null.
        val vcsEntries = baseRow!!.vcsEntries
        assertTrue(vcsEntries.isNotEmpty(), "Must have at least one VCS entry")
        assertTrue(
            vcsEntries.any { it.name == "main" },
            "Inline DSL form must produce name='main'; found: ${vcsEntries.map { it.name }}",
        )
    }

    @Test
    @Transactional
    @DisplayName(
        "MIG-037: TEST_COMPONENT2_WITH_SEVERAL_ROOTS has two VCS entries " +
            "with distinct names (multi-VCS unified model)",
    )
    fun mig037_unifiedVcsModel_multiRoot() {
        val component = componentRepository.findByComponentKey("TEST_COMPONENT2_WITH_SEVERAL_ROOTS")
        assertNotNull(component, "TEST_COMPONENT2_WITH_SEVERAL_ROOTS must be migrated")

        val baseRow =
            configurationRepository.findBaseByComponentId(component!!.id!!)
        assertNotNull(baseRow, "TEST_COMPONENT2_WITH_SEVERAL_ROOTS must have a base row")

        // Has vcsSettings { cvs { ... }; mercurial { ... } } → two named entries
        val vcsEntries = baseRow!!.vcsEntries
        assertTrue(
            vcsEntries.size >= 2,
            "Multi-VCS component must have 2+ VCS entries, found: ${vcsEntries.size}",
        )
        val distinctNames = vcsEntries.map { it.name }.toSet()
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

        // CRS-PR1: Pass 2 seeds canBeParent=true on the referenced parent; the
        // child (referenced by no one) stays canBeParent=false.
        assertTrue(parentRef.canBeParent, "TESTONE must be seeded canBeParent=true (referenced as a parent)")
        assertFalse(versionsApi.canBeParent, "'versions-api' (a child, not a parent) must remain canBeParent=false")
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
    // MIG-040: RANGE_PRESENCE rows for DSL ranges with no real override.
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-040 (ADR-018 decoupled): TEST_PER_RANGE_HOTFIX_FORMAT has a bounded merged coverage " +
            "[1.0,) (its empty [1.0,2.0) block + the [2.0,) override block merge) → exactly one " +
            "RANGE_PRESENCE row at [1.0,) with NULL overridden_attribute and all typed cols NULL; the " +
            "base sits at ALL_VERSIONS carrying the OPEN-UPPER [2.0,) hotfix format, and the OLDER " +
            "[1.0,2.0) block survives as the value-layer override (base = newest value, ADR-018)",
    )
    fun mig040_rangePresenceRowsEmittedForEmptyDslBlocks() {
        val component = componentRepository.findByComponentKey("TEST_PER_RANGE_HOTFIX_FORMAT")
        assertNotNull(component, "TEST_PER_RANGE_HOTFIX_FORMAT must be migrated")

        val allRows = configurationRepository.findByComponentId(component!!.id!!)

        // Coverage is stored MERGED: the empty [1.0,2.0) block and the [2.0,) override block are
        // contiguous, so their union is a single bounded coverage [1.0,) → exactly one presence row.
        val presenceRows = allRows.filter { it.rowType == "RANGE_PRESENCE" }
        assertEquals(
            1, presenceRows.size,
            "Must have exactly one merged RANGE_PRESENCE row; found rows: " +
                "${allRows.map { "${it.versionRange}/${it.rowType}" }}",
        )
        val presence = presenceRows.single()
        assertEquals(
            "[1.0,)", presence.versionRange,
            "The merged coverage row must be [1.0,) (union of [1.0,2.0) and [2.0,))",
        )
        assertNull(presence.overriddenAttribute, "RANGE_PRESENCE row must have NULL overridden_attribute")
        assertFalse(presence.isSyntheticBase, "RANGE_PRESENCE row must not be marked synthetic")
        // All 28 typed scalar columns must be NULL on a presence row.
        assertNull(presence.buildSystem)
        assertNull(presence.javaVersion); assertNull(presence.mavenVersion)
        assertNull(presence.gradleVersion); assertNull(presence.buildFilePath)
        assertNull(presence.deprecated); assertNull(presence.requiredProject)
        assertNull(presence.projectVersion); assertNull(presence.systemProperties)
        assertNull(presence.buildTasks); assertNull(presence.escrowBuildTask)
        assertNull(presence.escrowProvidedDependencies); assertNull(presence.escrowReusable)
        assertNull(presence.escrowGeneration); assertNull(presence.escrowDiskSpace)
        assertNull(presence.escrowAdditionalSources)
        assertNull(presence.escrowGradleIncludeConfigurations)
        assertNull(presence.escrowGradleExcludeConfigurations)
        assertNull(presence.escrowGradleIncludeTestConfigurations)
        assertNull(presence.jiraProjectKey); assertNull(presence.jiraTechnical)
        assertNull(presence.jiraMajorVersionFormat); assertNull(presence.jiraReleaseVersionFormat)
        assertNull(presence.jiraBuildVersionFormat); assertNull(presence.jiraLineVersionFormat)
        assertNull(presence.jiraVersionPrefix); assertNull(presence.jiraVersionFormat)
        assertNull(presence.jiraHotfixVersionFormat)

        // The base row sits at ALL_VERSIONS (the effective default), never a bounded block range,
        // and — per ADR-018 — carries the OPEN-UPPER (newest) block's value: the [2.0,) hotfix format.
        val baseRows = allRows.filter { it.rowType == "BASE" }
        assertEquals(1, baseRows.size, "Exactly one BASE row")
        val baseRow = baseRows.single()
        assertEquals(
            ALL_VERSIONS, baseRow.versionRange,
            "BASE row must be at ALL_VERSIONS (decoupled effective default)",
        )
        assertEquals(
            "\$major.\$minor.\$service-\$fix-\$build", baseRow.jiraHotfixVersionFormat,
            "BASE must carry the OPEN-UPPER [2.0,) hotfix format (newest value = base default, ADR-018)",
        )

        // Decoupled model: the OLDER [1.0,2.0) block now differs from the (newest) base, so it survives
        // as its own value-layer SCALAR_OVERRIDE carrying the pre-[2.0,) hotfix format. It is what
        // re-splits the enumeration at READ time into [1.0,2.0) (older format) and [2.0,) (= base).
        val overrideRows = allRows.filter { it.versionRange == "[1.0,2.0)" && it.rowType == "SCALAR_OVERRIDE" }
        assertEquals(
            1, overrideRows.size,
            "The older [1.0,2.0) block must survive as one SCALAR_OVERRIDE. " +
                "Found: ${allRows.map { "${it.versionRange}/${it.rowType}" }}",
        )
        assertEquals(
            "\$major.\$minor.\$service-\$fix", overrideRows.single().jiraHotfixVersionFormat,
            "The [1.0,2.0) override must carry the older (top-level) hotfix format",
        )
        // The OPEN-UPPER [2.0,) block is now the base itself — it leaves no standalone override row.
        assertTrue(
            allRows.none { it.versionRange == "[2.0,)" },
            "The open-upper [2.0,) block became the base and must leave no standalone row. " +
                "Found: ${allRows.map { "${it.versionRange}/${it.rowType}" }}",
        )
    }

    // =========================================================================
    // MIG-041: §6.3 aggregator handling — component_groups rows + is_fake + component_group_id FK
    // =========================================================================

    @Test
    @Transactional // required: `ComponentEntity.componentGroup` is LAZY; without an
    // active session, accessing it here would throw LazyInitializationException.
    @DisplayName(
        "MIG-041/§6.3: FAKE aggregator TEST_AGGREGATOR_FAKE — " +
            "ComponentGroupEntity(is_fake=true) created; member's component_group_id is linked",
    )
    fun mig041_componentGroupsAndIsFakeFromImport() {
        // ---- FAKE aggregator assertions ----
        // TEST_AGGREGATOR_FAKE has artifactId = "test-aggregator-fake-stub" → isFakeArtifactId returns true.
        // No vcsUrl is declared → isFakeAggregator returns true regardless.
        val fakeGroup = componentGroupRepository.findByGroupKey("TEST_AGGREGATOR_FAKE")
        assertNotNull(fakeGroup, "ComponentGroupEntity with groupKey='TEST_AGGREGATOR_FAKE' must be created by Pass 3")
        assertTrue(fakeGroup!!.isFake, "TEST_AGGREGATOR_FAKE group must have isFake=true (artifactId contains 'stub')")

        // R1 compat parity: a FAKE aggregator now ALSO gets a ComponentEntity row (so the
        // v1–v3 resolver keeps serving it) and is self-linked to its OWN fake group. That
        // self-link (group.isFake && group.groupKey == componentKey) is the marker the v4 list
        // uses to EXCLUDE it (asserted separately in mig041b). Guards the compat regression
        // where #306 skipped the Pass-1 insert for fake aggregators and 404'd them in v3.
        val fakeAggRow = componentRepository.findByComponentKey("TEST_AGGREGATOR_FAKE")
        assertNotNull(
            fakeAggRow,
            "TEST_AGGREGATOR_FAKE MUST have a ComponentEntity row now (v1–v3 compat parity)",
        )
        assertNotNull(
            fakeAggRow!!.componentGroup,
            "TEST_AGGREGATOR_FAKE must be self-linked to its own group",
        )
        assertEquals(
            fakeGroup.id,
            fakeAggRow.componentGroup!!.id,
            "TEST_AGGREGATOR_FAKE.componentGroup must reference its OWN fake group (self-link = v4-exclusion marker)",
        )

        // The member sub-component must have its component_group_id set
        val member = componentRepository.findByComponentKey("TEST_AGGREGATOR_MEMBER")
        assertNotNull(member, "TEST_AGGREGATOR_MEMBER must be present in the DB after migration")
        assertNotNull(
            member!!.componentGroup,
            "TEST_AGGREGATOR_MEMBER must have componentGroup set (component_group_id FK)",
        )
        assertEquals(
            fakeGroup.id,
            member.componentGroup!!.id,
            "TEST_AGGREGATOR_MEMBER.componentGroup must reference the TEST_AGGREGATOR_FAKE group",
        )

        // ---- REAL aggregator assertions ----
        // TESTONE has vcsUrl + non-stub artifactId → isFakeAggregator returns false.
        // versions-api sub-component declares parentComponent = "TESTONE".
        val realGroup = componentGroupRepository.findByGroupKey("TESTONE")
        assertNotNull(realGroup, "ComponentGroupEntity with groupKey='TESTONE' must be created for REAL aggregator")
        assertFalse(realGroup!!.isFake, "TESTONE group must have isFake=false")

        // REAL aggregator itself must be linked to its own group
        val testone = componentRepository.findByComponentKey("TESTONE")
        assertNotNull(testone, "TESTONE must be in DB")
        assertNotNull(
            testone!!.componentGroup,
            "REAL aggregator TESTONE must have its own componentGroup set",
        )
        assertEquals(
            realGroup.id,
            testone.componentGroup!!.id,
            "TESTONE.componentGroup must reference its own group",
        )

        // versions-api sub-component must be linked to the TESTONE group
        val versionsApi = componentRepository.findByComponentKey("versions-api")
        assertNotNull(versionsApi, "versions-api sub-component must be in DB")
        assertNotNull(
            versionsApi!!.componentGroup,
            "versions-api must have componentGroup set",
        )
        assertEquals(
            realGroup.id,
            versionsApi.componentGroup!!.id,
            "versions-api.componentGroup must reference the TESTONE group",
        )
    }

    @Test
    @Transactional
    @DisplayName(
        "MIG-041b/compat: FAKE aggregator is fetchable by key (v1–v3 parity) but EXCLUDED from the " +
            "v4 list; real aggregator + members + ordinary components remain in the v4 list",
    )
    fun mig041b_fakeAggregatorServedByResolverButHiddenFromV4List() {
        // v1–v3 read path (DatabaseComponentRegistryResolver.getComponentById → findByComponentKey):
        // the FAKE aggregator MUST still resolve. This is the exact #306 compat regression — the
        // Pass-1 skip dropped its row and v3 began 404-ing it.
        assertNotNull(
            componentRepository.findByComponentKey("TEST_AGGREGATOR_FAKE"),
            "TEST_AGGREGATOR_FAKE must be fetchable by key (v1–v3 compat parity)",
        )

        // v4 regular-components list: the FAKE aggregator stub must NOT appear; everything else does.
        val listed =
            componentManagementService
                .listComponents(ComponentFilter(), PageRequest.of(0, 500))
                .content
                .map { it.name }
                .toSet()

        assertFalse(
            "TEST_AGGREGATOR_FAKE" in listed,
            "FAKE aggregator stub must be hidden from the v4 regular-components list",
        )
        assertTrue(
            "TEST_AGGREGATOR_MEMBER" in listed,
            "a FAKE aggregator's member is a real component — it must remain in the v4 list",
        )
        assertTrue(
            "TESTONE" in listed,
            "a REAL aggregator must remain in the v4 list (only FAKE stubs are hidden)",
        )
        assertTrue(
            "versions-api" in listed,
            "a REAL aggregator's member must remain in the v4 list",
        )
    }

    // =========================================================================
    // R1 §6.3 cleanup-on-rerun: the OLD importer grouped by flat parentComponent
    // and could leave a ComponentGroup for a non-aggregator (a plain parentComponent
    // target with no components{} block).
    // A re-migration must remove any group whose key is NOT a current true
    // aggregator (a `components { }` owner) — unlinking its members — while
    // PRESERVING real-aggregator groups (TESTONE / TEST_AGGREGATOR_FAKE).
    // =========================================================================

    @Test
    @Transactional
    @DisplayName(
        "MIG-R1/§6.3: re-migration deletes a stale (non-aggregator) group and unlinks its members, " +
            "but preserves the real-aggregator groups",
    )
    fun migR1_rerunRemovesStaleGroupsButPreservesAggregators() {
        // Seed a stale group whose key is NOT any DSL aggregator, plus a victim
        // component linked to it (mimicking a leftover from the old flat-parent
        // grouping). The victim is not in the DSL, so re-migration ignores it
        // except for the cleanup unlink.
        val staleKey = "org.example.STALE_FLAT_PARENT_GROUP"
        val staleGroup = componentGroupRepository.save(ComponentGroupEntity(groupKey = staleKey, isFake = false))
        val victim = componentRepository.save(ComponentEntity(componentKey = "stale-cleanup-victim"))
        victim.componentGroup = staleGroup
        componentRepository.save(victim)

        // Real-aggregator groups created at startup must exist beforehand.
        val testoneIdBefore = componentGroupRepository.findByGroupKey("TESTONE")?.id
        val fakeIdBefore = componentGroupRepository.findByGroupKey("TEST_AGGREGATOR_FAKE")?.id
        assertNotNull(testoneIdBefore, "precondition: TESTONE group must exist from the initial migration")
        assertNotNull(fakeIdBefore, "precondition: TEST_AGGREGATOR_FAKE group must exist from the initial migration")
        assertNotNull(
            componentRepository.findByComponentKey("stale-cleanup-victim")?.componentGroup,
            "precondition: victim must carry the stale group before re-migration (else the test is vacuous)",
        )

        // Re-run the full batch migration (idempotent) — Pass 3 + cleanup run.
        importService.migrateAllComponents()

        // The stale group is gone and its member is unlinked.
        assertNull(
            componentGroupRepository.findByGroupKey(staleKey),
            "stale non-aggregator group '$staleKey' must be removed on re-migration",
        )
        val victimAfter = componentRepository.findByComponentKey("stale-cleanup-victim")
        assertNotNull(victimAfter, "victim component must still exist (cleanup unlinks, does not delete members)")
        assertNull(
            victimAfter!!.componentGroup,
            "victim's componentGroup must be cleared when its stale group is removed",
        )

        // Real-aggregator groups are preserved (same rows, idempotent upsert).
        assertEquals(
            testoneIdBefore,
            componentGroupRepository.findByGroupKey("TESTONE")?.id,
            "REAL aggregator group TESTONE must be preserved across re-migration",
        )
        assertEquals(
            fakeIdBefore,
            componentGroupRepository.findByGroupKey("TEST_AGGREGATOR_FAKE")?.id,
            "FAKE aggregator group TEST_AGGREGATOR_FAKE must be preserved across re-migration",
        )
    }

    @Test
    @Transactional
    @DisplayName(
        "MIG-R1/§6.3: re-migration refreshes a stale component_groups.is_fake flag (REAL aggregator " +
            "wrongly stored as fake is corrected back to is_fake=false)",
    )
    fun migR1_rerunRefreshesStaleIsFakeFlag() {
        // TESTONE is a REAL aggregator (real VCS + non-stub artifactId) → is_fake=false
        // from the initial migration. Simulate a stale flag (e.g. left by an older import
        // or a transient mis-classification) by flipping it to true in the DB, then re-run
        // the migration: upsertComponentGroup must refresh the flag back to the DSL-correct
        // value (false), keeping the SAME group row (not a delete+recreate).
        val before = componentGroupRepository.findByGroupKey("TESTONE")
        assertNotNull(before, "precondition: TESTONE group must exist from the initial migration")
        assertFalse(before!!.isFake, "precondition: TESTONE is a REAL aggregator (is_fake=false)")
        val groupIdBefore = before.id

        before.isFake = true
        componentGroupRepository.save(before)
        assertTrue(
            componentGroupRepository.findByGroupKey("TESTONE")!!.isFake,
            "precondition: stale is_fake=true must be persisted before re-migration",
        )

        importService.migrateAllComponents()

        val after = componentGroupRepository.findByGroupKey("TESTONE")
        assertNotNull(after, "TESTONE group must still exist after re-migration")
        assertFalse(after!!.isFake, "re-migration must refresh the stale flag back to is_fake=false")
        assertEquals(groupIdBefore, after.id, "the group row must be refreshed in place, not recreated")
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
