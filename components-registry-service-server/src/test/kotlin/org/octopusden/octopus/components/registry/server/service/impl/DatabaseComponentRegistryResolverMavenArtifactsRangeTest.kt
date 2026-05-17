package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * TDD regression tests for RES-C: getMavenArtifactParameters must return per-range
 * groupPattern/artifactPattern by walking the EscrowModule view rather than
 * returning the component-level artifactIds for every range.
 *
 * Bug: when a component has DISTRIBUTION_MAVEN marker rows overriding the GAV per
 * range, the old implementation ignored the overrides and returned the same
 * component-level artifact IDs for every range. The fixed implementation consults
 * config.distribution?.GAV() per EscrowModuleConfig, falling back to component-level
 * artifactIds only when no per-range GAV is present.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class DatabaseComponentRegistryResolverMavenArtifactsRangeTest {

    // ========================================================================
    // Infrastructure
    // ========================================================================

    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )
    }

    // ========================================================================
    // Entity helpers
    // ========================================================================

    private fun makeComponent(key: String): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun makeBase(
        component: ComponentEntity,
        versionRange: String = ALL_VERSIONS,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = null,
            rowType = "BASE",
            deprecated = false,
        )

    private fun makeMarkerRow(
        component: ComponentEntity,
        versionRange: String,
        attribute: String,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = attribute,
            rowType = "MARKER",
        )

    private fun addMavenArtifact(
        config: ComponentConfigurationEntity,
        groupPattern: String,
        artifactPattern: String,
        sortOrder: Int = 0,
    ) {
        config.mavenArtifacts.add(
            DistributionMavenArtifactEntity(
                componentConfiguration = config,
                groupPattern = groupPattern,
                artifactPattern = artifactPattern,
                sortOrder = sortOrder,
            ),
        )
    }

    /**
     * Append a row to `componentEntity.artifactIds` — the component-level fallback table
     * populated by `ImportServiceImpl` from `EscrowModuleConfig.artifactIdPattern`. For
     * components whose DSL omits an explicit `artifactId` line, the inherited
     * `Defaults.artifactId = ANY_ARTIFACT (/[\w-\.]+/)` is written verbatim as the
     * `artifactPattern`. See `ImportServiceImpl.kt:893-902`.
     */
    private fun addComponentLevelArtifact(
        component: ComponentEntity,
        groupPattern: String,
        artifactPattern: String,
        sortOrder: Int = component.artifactIds.size,
    ) {
        component.artifactIds.add(
            ComponentArtifactIdEntity(
                component = component,
                groupPattern = groupPattern,
                artifactPattern = artifactPattern,
                sortOrder = sortOrder,
            ),
        )
    }

    private fun makeRangePresenceRow(
        component: ComponentEntity,
        versionRange: String,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = null,
            rowType = "RANGE_PRESENCE",
        )

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    // ========================================================================
    // Tests
    // ========================================================================

    @Test
    @DisplayName(
        "RES-C-001: per-range DISTRIBUTION_MAVEN marker overrides groupPattern " +
            "(bug-C-component-A shape: two ranges with distinct groupId per range)",
    )
    fun `RES-C-001 getMavenArtifactParameters returns per-range groupPattern from marker override`() {
        // bug-C-component-A shape:
        //   BASE at [1.1,) with maven artifact (com.example.ic, bug-C-component-A)
        //   MARKER distribution.maven at [1.0,1.1) with maven artifact (com.example, bug-C-component-A)
        val comp = makeComponent("bug-C-component-A-like")
        comp.distributionExplicit = true

        // Component-level fallback: ImportServiceImpl writes this from the BASE
        // EscrowModuleConfig.artifactIdPattern — non-marker ranges return it verbatim.
        addComponentLevelArtifact(comp, "com.example.ic", "bug-C-component-A")

        val base = makeBase(comp, "[1.1,)")
        addMavenArtifact(base, "com.example.ic", "bug-C-component-A")

        val markerOld = makeMarkerRow(comp, "[1.0,1.1)", "distribution.maven")
        addMavenArtifact(markerOld, "com.example", "bug-C-component-A")

        comp.configurations.addAll(listOf(base, markerOld))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("bug-C-component-A-like")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeOld = result["[1.0,1.1)"]
        assertNotNull(rangeOld, "[1.0,1.1) entry must be present")
        assertEquals("com.example", rangeOld!!.groupPattern, "[1.0,1.1) groupPattern must be com.example")
        assertEquals("bug-C-component-A", rangeOld.artifactPattern, "[1.0,1.1) artifactPattern must be bug-C-component-A")

        val rangeNew = result["[1.1,)"]
        assertNotNull(rangeNew, "[1.1,) entry must be present")
        assertEquals(
            "com.example.ic",
            rangeNew!!.groupPattern,
            "[1.1,) groupPattern must be com.example.ic",
        )
        assertEquals("bug-C-component-A", rangeNew.artifactPattern, "[1.1,) artifactPattern must be bug-C-component-A")
    }

    @Test
    @DisplayName(
        "RES-C-002: per-range DISTRIBUTION_MAVEN marker override with multiple artifacts " +
            "(bug-C-component-B shape: multi-artifact CSV, two ranges)",
    )
    fun `RES-C-002 getMavenArtifactParameters handles multi-artifact CSV override`() {
        // bug-C-component-B shape:
        //   BASE at [03.51.29.15,) with maven artifact (com.example.cardsmodel2.dummy, bug-C-component-B)
        //   MARKER distribution.maven at (,03.51.29.15) with two artifacts:
        //     (com.example.cardsmodel2, bug-C-fixture-v2) and (com.example.cardsmodel, bug-C-fixture-legacy)
        val comp = makeComponent("bug-C-fixture-B-shape")
        comp.distributionExplicit = true

        // Component-level fallback (production import writes this from BASE artifactIdPattern).
        addComponentLevelArtifact(comp, "com.example.cardsmodel2.dummy", "bug-C-component-B")

        val base = makeBase(comp, "[03.51.29.15,)")
        addMavenArtifact(base, "com.example.cardsmodel2.dummy", "bug-C-component-B", sortOrder = 0)

        val markerOld = makeMarkerRow(comp, "(,03.51.29.15)", "distribution.maven")
        addMavenArtifact(markerOld, "com.example.cardsmodel2", "bug-C-fixture-v2", sortOrder = 0)
        addMavenArtifact(markerOld, "com.example.cardsmodel", "bug-C-fixture-legacy", sortOrder = 1)

        comp.configurations.addAll(listOf(base, markerOld))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("bug-C-fixture-B-shape")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeOld = result["(,03.51.29.15)"]
        assertNotNull(rangeOld, "(,03.51.29.15) entry must be present")
        // groupPattern = first artifact's groupId
        assertEquals(
            "com.example.cardsmodel2",
            rangeOld!!.groupPattern,
            "(,03.51.29.15) groupPattern must be first artifact's groupId",
        )
        // artifactPattern = CSV join of all artifact IDs in sort order
        assertEquals(
            "bug-C-fixture-v2,bug-C-fixture-legacy",
            rangeOld.artifactPattern,
            "(,03.51.29.15) artifactPattern must be CSV of both artifact IDs",
        )

        val rangeNew = result["[03.51.29.15,)"]
        assertNotNull(rangeNew, "[03.51.29.15,) entry must be present")
        assertEquals(
            "com.example.cardsmodel2.dummy",
            rangeNew!!.groupPattern,
            "[03.51.29.15,) groupPattern must be com.example.cardsmodel2.dummy",
        )
        assertEquals("bug-C-component-B", rangeNew.artifactPattern, "[03.51.29.15,) artifactPattern must be bug-C-component-B")
    }

    @Test
    @DisplayName(
        "RES-C-003: component with single ALL_VERSIONS range and no marker overrides " +
            "falls back to component-level artifactIds",
    )
    fun `RES-C-003 getMavenArtifactParameters falls back to component-level artifactIds when no GAV override`() {
        // No per-range overrides: only a BASE row with maven artifact at ALL_VERSIONS
        // and a component-level row (production-shape: import writes both from a single
        // BASE EscrowModuleConfig).
        val comp = makeComponent("simple-component")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example", "simple-lib")

        val base = makeBase(comp, ALL_VERSIONS)
        addMavenArtifact(base, "com.example", "simple-lib")

        comp.configurations.add(base)
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("simple-component")

        assertEquals(1, result.size, "Expected one range entry for ALL_VERSIONS")
        val entry = result[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals("com.example", entry!!.groupPattern, "groupPattern must be com.example")
        assertEquals("simple-lib", entry.artifactPattern, "artifactPattern must be simple-lib")
    }

    // ========================================================================
    // RES-C-prime tests (Contract B): V1 wildcard preservation
    // ========================================================================
    //
    // RES-C-prime: V1 wildcard preservation for `/maven-artifacts`.
    //
    // The V1 in-memory resolver returns `EscrowModuleConfig.artifactIdPattern`
    // verbatim. When the DSL has no explicit `artifactId` line, the inherited
    // default is `Defaults.artifactId = ANY_ARTIFACT (/[\w-\.]+/)`, so prod
    // emits the wildcard literal for every range. PR-C's V2 implementation
    // unconditionally extracted artifactIds from `config.distribution?.GAV()`,
    // which silently changed the public-API semantic for any DB-routed
    // component without an explicit `artifactId` declaration. The fix gates
    // GAV-extraction on a per-range `DISTRIBUTION_MAVEN` MARKER row being
    // present for the range; otherwise we return `componentLevelFallback`
    // which on V1-import path is the wildcard literal.

    @Test
    @DisplayName(
        "RES-C-004: BASE has per-config GAV but no per-range DISTRIBUTION_MAVEN marker " +
            "→ component-level wildcard wins; do not synthesize artifactIds from GAV",
    )
    fun `RES-C-004 BASE GAV without marker returns component-level wildcard`() {
        // Production shape: a database-backed component with no explicit `artifactId` line
        // in its DSL. Defaults inject the wildcard, ImportServiceImpl writes a single
        // component-level row with `artifactPattern = "[\w-\.]+"`. The per-config
        // distribution carries the concrete GAV CSV (`groupId:artifactId:packaging,...`)
        // for downstream packaging-resolution use, but the `/maven-artifacts` endpoint
        // must return the wildcard, matching V1.
        val comp = makeComponent("res-c-prime-fixture-single-range")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.test", V1_WILDCARD)

        val base = makeBase(comp, ALL_VERSIONS)
        // Per-config maven-artifact rows simulate the inherited GAV that PR-C
        // would have extracted into a CSV. Two entries to make the regression
        // visible (a CSV like "art-a,art-b" would land here pre-fix).
        addMavenArtifact(base, "com.example.test", "art-a", sortOrder = 0)
        addMavenArtifact(base, "com.example.test", "art-b", sortOrder = 1)

        comp.configurations.add(base)
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("res-c-prime-fixture-single-range")

        assertEquals(1, result.size, "Expected exactly one range entry (no markers)")
        val entry = result[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals("com.example.test", entry!!.groupPattern, "groupPattern must come from component-level fallback")
        assertEquals(
            V1_WILDCARD,
            entry.artifactPattern,
            "artifactPattern must be the V1 wildcard literal — NOT the GAV-synthesized CSV",
        )
    }

    @Test
    @DisplayName(
        "RES-C-004b: two ranges (BASE + RANGE_PRESENCE) with per-config GAV but no markers " +
            "→ both ranges return the V1 wildcard via component-level fallback",
    )
    fun `RES-C-004b two ranges without markers both return wildcard`() {
        val comp = makeComponent("res-c-prime-fixture-two-ranges")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.test", V1_WILDCARD)

        val base = makeBase(comp, "(,1.0.107)")
        addMavenArtifact(base, "com.example.test", "art-a", sortOrder = 0)
        // RANGE_PRESENCE row brings the second range into the toEscrowModule enumeration
        // without introducing a DISTRIBUTION_MAVEN marker — exactly the prod shape where
        // a component has multiple ranges but no per-range maven override.
        val presence = makeRangePresenceRow(comp, "[1.0.107,)")

        comp.configurations.addAll(listOf(base, presence))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("res-c-prime-fixture-two-ranges")

        assertEquals(2, result.size, "Expected two range entries")
        for (range in listOf("(,1.0.107)", "[1.0.107,)")) {
            val entry = result[range]
            assertNotNull(entry, "$range entry must be present")
            assertEquals("com.example.test", entry!!.groupPattern, "$range groupPattern must be from fallback")
            assertEquals(V1_WILDCARD, entry.artifactPattern, "$range artifactPattern must be the V1 wildcard")
        }
    }

    @Test
    @DisplayName(
        "RES-C-006: multi-artifact component-level CSV is re-joined in DSL declaration order " +
            "(sort_order ASC), NOT in UUID order — regression guard for VAL-006",
    )
    fun `RES-C-006 component-level multi-artifact CSV preserves DSL declaration order`() {
        // VAL-006 failure mode from TC build 3456: a sort-by-UUID fallback for
        // componentEntity.artifactIds joined the CSV in random order. V1 reads the
        // raw DSL `artifactIdPattern` string ("art-core,art-cli,art-xml") verbatim;
        // V2 must re-join the imported rows in the same declaration order via
        // `sort_order` ASC.
        val comp = makeComponent("res-c-prime-fixture-order-preservation")
        comp.distributionExplicit = true

        // Add artifacts in NON-alphabetical order to force the test to fail if
        // the resolver falls back to alphabetical (or UUID-random) sorting.
        addComponentLevelArtifact(comp, "com.example.test", "art-core", sortOrder = 0)
        addComponentLevelArtifact(comp, "com.example.test", "art-cli", sortOrder = 1)
        addComponentLevelArtifact(comp, "com.example.test", "art-xml", sortOrder = 2)
        addComponentLevelArtifact(comp, "com.example.test", "art-zeta", sortOrder = 3)
        addComponentLevelArtifact(comp, "com.example.test", "art-alpha", sortOrder = 4)

        val base = makeBase(comp, ALL_VERSIONS)
        comp.configurations.add(base)
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("res-c-prime-fixture-order-preservation")

        assertEquals(1, result.size, "Expected one range entry")
        val entry = result[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals(
            "art-core,art-cli,art-xml,art-zeta,art-alpha",
            entry!!.artifactPattern,
            "artifactPattern CSV must preserve sort_order ASC (DSL declaration order), " +
                "NOT collapse to alphabetical (`art-alpha,art-cli,...`) or UUID-random order.",
        )
    }

    @Test
    @DisplayName(
        "RES-C-005: mixed — BASE+GAV, MARKER for range B only → range B uses GAV, range A uses fallback",
    )
    fun `RES-C-005 marker-gated GAV extraction only for marked ranges`() {
        // Demonstrates that the per-range MARKER gate is the discriminator:
        //   - range A (BASE, no marker)            → wildcard from component-level fallback
        //   - range B (DISTRIBUTION_MAVEN marker)  → GAV-derived CSV
        val comp = makeComponent("res-c-prime-fixture-mixed")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.test", V1_WILDCARD)

        val base = makeBase(comp, "(,1.0)")
        addMavenArtifact(base, "com.example.test", "art-x", sortOrder = 0)

        val markerNew = makeMarkerRow(comp, "[1.0,)", "distribution.maven")
        addMavenArtifact(markerNew, "com.example.new", "real-artifact-id", sortOrder = 0)

        comp.configurations.addAll(listOf(base, markerNew))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("res-c-prime-fixture-mixed")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeNoMarker = result["(,1.0)"]
        assertNotNull(rangeNoMarker, "(,1.0) entry must be present")
        assertEquals(
            "com.example.test",
            rangeNoMarker!!.groupPattern,
            "(,1.0) groupPattern must be component-level fallback (no marker for this range)",
        )
        assertEquals(
            V1_WILDCARD,
            rangeNoMarker.artifactPattern,
            "(,1.0) artifactPattern must be wildcard — marker is on a different range",
        )

        val rangeWithMarker = result["[1.0,)"]
        assertNotNull(rangeWithMarker, "[1.0,) entry must be present")
        assertEquals(
            "com.example.new",
            rangeWithMarker!!.groupPattern,
            "[1.0,) groupPattern must come from the marker's GAV (override is active)",
        )
        assertEquals(
            "real-artifact-id",
            rangeWithMarker.artifactPattern,
            "[1.0,) artifactPattern must come from the marker's GAV (override is active)",
        )
    }

    // ========================================================================
    // MIG-047 read-path coverage (PR #240 P2 follow-up)
    // ========================================================================
    //
    // Symmetric coverage for the GROUP_ARTIFACT_PATTERN marker emitted by
    // `ImportServiceImpl.attachMavenArtifactsFromGroupArtifact` (RES-C tests
    // above cover the same read-path for DISTRIBUTION_MAVEN). The resolver
    // accepts either marker name when looking up per-range maven coordinates;
    // these tests guard that branch so it cannot regress independently of the
    // import-side unit tests.

    @Test
    @DisplayName(
        "MIG-047-RES-001: per-range GROUP_ARTIFACT_PATTERN marker overrides groupPattern " +
            "even when neither side carries an explicit distribution.GAV",
    )
    fun `MIG-047-RES-001 getMavenArtifactParameters returns per-range groupPattern from GROUP_ARTIFACT_PATTERN marker`() {
        // MIG-047 import-path shape:
        //   BASE at [1.0,1.1) with maven artifact (com.example, alpha-fixture)
        //   GROUP_ARTIFACT_PATTERN MARKER at [1.1,) with maven artifact (com.example.ic, alpha-fixture)
        // Neither config had an explicit distribution { gav = … } block — the maven-artifact
        // rows were synthesized from per-range groupId/artifactId by the import path.
        val comp = makeComponent("alpha-fixture-mig047-resolver")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example", "alpha-fixture")

        val base = makeBase(comp, "[1.0,1.1)")
        addMavenArtifact(base, "com.example", "alpha-fixture")

        val marker = makeMarkerRow(comp, "[1.1,)", MarkerAttributes.GROUP_ARTIFACT_PATTERN)
        addMavenArtifact(marker, "com.example.ic", "alpha-fixture")

        comp.configurations.addAll(listOf(base, marker))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("alpha-fixture-mig047-resolver")

        assertEquals(2, result.size, "Expected two range entries (base + GROUP_ARTIFACT_PATTERN override)")

        val rangeBase = result["[1.0,1.1)"]
        assertNotNull(rangeBase, "[1.0,1.1) entry must be present")
        assertEquals("com.example", rangeBase!!.groupPattern)
        assertEquals("alpha-fixture", rangeBase.artifactPattern)

        val rangeOverride = result["[1.1,)"]
        assertNotNull(rangeOverride, "[1.1,) entry must be present")
        assertEquals(
            "com.example.ic",
            rangeOverride!!.groupPattern,
            "[1.1,) groupPattern must come from GROUP_ARTIFACT_PATTERN marker",
        )
        assertEquals("alpha-fixture", rangeOverride.artifactPattern)
    }

    @Test
    @DisplayName(
        "MIG-047-RES-002: per-range GROUP_ARTIFACT_PATTERN marker with multi-artifact CSV " +
            "(artifactId list grows across ranges, same groupId)",
    )
    fun `MIG-047-RES-002 getMavenArtifactParameters handles GROUP_ARTIFACT_PATTERN with multi-artifact CSV`() {
        val comp = makeComponent("beta-fixture-mig047-csv")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.mcloud", "core-a")

        val base = makeBase(comp, "[1.0,)")
        addMavenArtifact(base, "com.example.mcloud", "core-a")

        val marker = makeMarkerRow(comp, "[2.0,)", MarkerAttributes.GROUP_ARTIFACT_PATTERN)
        addMavenArtifact(marker, "com.example.mcloud", "core-a", sortOrder = 0)
        addMavenArtifact(marker, "com.example.mcloud", "core-b", sortOrder = 1)
        addMavenArtifact(marker, "com.example.mcloud", "core-c", sortOrder = 2)

        comp.configurations.addAll(listOf(base, marker))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("beta-fixture-mig047-csv")

        assertEquals(2, result.size, "Expected two range entries")
        val rangeOverride = result["[2.0,)"]
        assertNotNull(rangeOverride, "[2.0,) entry must be present")
        assertEquals("com.example.mcloud", rangeOverride!!.groupPattern)
        assertEquals(
            "core-a,core-b,core-c",
            rangeOverride.artifactPattern,
            "[2.0,) artifactPattern must be CSV of all three GROUP_ARTIFACT_PATTERN children in sort order",
        )
    }

    @Test
    @DisplayName(
        "MIG-047-RES-003: when both DISTRIBUTION_MAVEN and GROUP_ARTIFACT_PATTERN markers " +
            "exist on the same versionRange, DISTRIBUTION_MAVEN wins deterministically",
    )
    fun `MIG-047-RES-003 same-range conflict — DISTRIBUTION_MAVEN takes precedence over GROUP_ARTIFACT_PATTERN`() {
        // Conflict shape: a V4 user added a distribution.maven override on a range that
        // already had an import-managed GROUP_ARTIFACT_PATTERN row (V4 createFieldOverride
        // only de-dupes by `overriddenAttribute`, so this pair is reachable). With the
        // pre-fix `associateBy { it.versionRange }`, whichever row sits last in
        // `componentEntity.configurations` wins — non-deterministic because @OneToMany
        // does not specify iteration order. Post-fix, the explicit DISTRIBUTION_MAVEN
        // override wins regardless of row-order (mirrors the V4 user's stated intent;
        // GROUP_ARTIFACT_PATTERN is import-internal and supplanted).
        val comp = makeComponent("gamma-fixture-mig047-conflict")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example", "gamma-fixture")

        val base = makeBase(comp, "[1.0,1.1)")
        addMavenArtifact(base, "com.example", "gamma-fixture")

        // Build a deliberately adversarial ordering: GROUP_ARTIFACT_PATTERN appears AFTER
        // DISTRIBUTION_MAVEN in the configurations list. The pre-fix `associateBy` keeps
        // the LAST entry with the same key — so GROUP_ARTIFACT_PATTERN wins under the bug.
        val distributionMaven = makeMarkerRow(comp, "[1.1,)", MarkerAttributes.DISTRIBUTION_MAVEN)
        addMavenArtifact(distributionMaven, "com.example.user-explicit", "gamma-fixture")

        val groupArtifactPattern = makeMarkerRow(comp, "[1.1,)", MarkerAttributes.GROUP_ARTIFACT_PATTERN)
        addMavenArtifact(groupArtifactPattern, "com.example.import-internal", "gamma-fixture")

        comp.configurations.addAll(listOf(base, distributionMaven, groupArtifactPattern))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("gamma-fixture-mig047-conflict")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeOverride = result["[1.1,)"]
        assertNotNull(rangeOverride, "[1.1,) entry must be present")
        assertEquals(
            "com.example.user-explicit",
            rangeOverride!!.groupPattern,
            "DISTRIBUTION_MAVEN must take precedence over GROUP_ARTIFACT_PATTERN on the same range",
        )
        assertEquals("gamma-fixture", rangeOverride.artifactPattern)
    }

    // ========================================================================
    // Coverage gaps (out of scope for this PR, tracked for follow-up)
    // ========================================================================
    //
    // RES-C-006 (TODO): synthetic-base + DISTRIBUTION_MAVEN MARKER. Per
    // EntityMappers.kt:106, `toEscrowModule` suppresses the synthetic-base range
    // `(,)` when overrides are present. A MARKER row whose `versionRange` is the
    // synthetic placeholder ends up in `markerRanges` here but is never emitted
    // by `toEscrowModule`, so the resolver short-circuits to fallback for any
    // real range — which is the correct outcome. No reproducer in production
    // today, but the path is untested and worth a dedicated fixture if the
    // schema ever evolves.

    // ========================================================================
    // Companion
    // ========================================================================

    companion object {
        /** Must match EscrowConfigurationLoader.ALL_VERSIONS */
        private const val ALL_VERSIONS = "(,0),[0,)"

        /** V1 wildcard placeholder emitted by `Defaults.artifactId = ANY_ARTIFACT`. */
        private const val V1_WILDCARD = "[\\w-\\.]+"
    }
}
