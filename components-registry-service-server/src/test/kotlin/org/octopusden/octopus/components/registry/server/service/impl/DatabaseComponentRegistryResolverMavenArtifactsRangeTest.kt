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
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.mapper.toEscrowModule
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
        @Suppress("UNUSED_PARAMETER") sortOrder: Int = 0,
    ) {
        // Each call adds one ownership mapping. Multi-artifact callers should pass a comma-joined
        // `artifactPattern` (one EXPLICIT mapping with ordered tokens), matching the new model.
        component.addOwnershipMapping(groupPattern, artifactPattern)
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
        "RES-C-001: per-range GROUP_ARTIFACT_PATTERN marker overrides groupPattern " +
            "(bug-C-component-A shape: two ranges with distinct groupId per range)",
    )
    fun `RES-C-001 getMavenArtifactParameters returns per-range groupPattern from marker override`() {
        // bug-C-component-A shape:
        //   BASE at [1.1,) with maven artifact (com.example.ic, bug-C-component-A)
        //   MARKER GROUP_ARTIFACT_PATTERN at [1.0,1.1) with maven artifact (com.example, bug-C-component-A)
        //
        // V1-contract note (RES-C-007/008): only GROUP_ARTIFACT_PATTERN markers
        // override /maven-artifacts' (groupPattern, artifactPattern). The
        // historical version of this test used DISTRIBUTION_MAVEN — which is
        // emitted by the importer for every per-range `distribution { gav = … }`
        // block, regardless of whether the DSL explicitly redefined
        // `groupId`/`artifactId` at the version level. That made the read-path
        // emit GAV-derived tokens instead of the inherited contract field. Now
        // we model the legitimate override (explicit per-range groupId/artifactId
        // in the DSL) via GROUP_ARTIFACT_PATTERN.
        val comp = makeComponent("bug-C-component-A-like")
        comp.distributionExplicit = true

        // Component-level fallback: ImportServiceImpl writes this from the BASE
        // EscrowModuleConfig.artifactIdPattern — non-marker ranges return it verbatim.
        addComponentLevelArtifact(comp, "com.example.ic", "bug-C-component-A")

        val base = makeBase(comp, "[1.1,)")
        addMavenArtifact(base, "com.example.ic", "bug-C-component-A")

        // Per-range ownership override (new model: a mapping with the override range).
        comp.addOwnershipMapping("com.example", "bug-C-component-A", "[1.0,1.1)")

        comp.configurations.add(base)
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
        "RES-C-002: per-range GROUP_ARTIFACT_PATTERN marker override with multiple artifacts " +
            "(bug-C-component-B shape: multi-artifact CSV, two ranges)",
    )
    fun `RES-C-002 getMavenArtifactParameters handles multi-artifact CSV override`() {
        // bug-C-component-B shape:
        //   BASE at [03.51.29.15,) with maven artifact (com.example.cardsmodel2.dummy, bug-C-component-B)
        //   MARKER GROUP_ARTIFACT_PATTERN at (,03.51.29.15) with two artifacts:
        //     (com.example.cardsmodel2, bug-C-fixture-v2) and (com.example.cardsmodel, bug-C-fixture-legacy)
        // V1-contract update: switched from DISTRIBUTION_MAVEN to GROUP_ARTIFACT_PATTERN — see RES-C-001 comment.
        val comp = makeComponent("bug-C-fixture-B-shape")
        comp.distributionExplicit = true

        // Component-level fallback (production import writes this from BASE artifactIdPattern).
        addComponentLevelArtifact(comp, "com.example.cardsmodel2.dummy", "bug-C-component-B")

        val base = makeBase(comp, "[03.51.29.15,)")
        addMavenArtifact(base, "com.example.cardsmodel2.dummy", "bug-C-component-B", sortOrder = 0)

        // Per-range ownership override: one group + CSV artifacts (the real DSL shape; the old
        // two-different-group marker never occurred — attachMavenArtifactsFromGroupArtifact used one group).
        comp.addOwnershipMapping("com.example.cardsmodel2", "bug-C-fixture-v2,bug-C-fixture-legacy", "(,03.51.29.15)")

        comp.configurations.add(base)
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
        "RES-C-004b: two enumerated ranges (split by a non-ownership override edge) with per-config " +
            "GAV but no maven marker → both ranges return the V1 wildcard via component-level fallback",
    )
    fun `RES-C-004b two ranges without markers both return wildcard`() {
        val comp = makeComponent("res-c-prime-fixture-two-ranges")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.test", V1_WILDCARD)

        val base = makeBase(comp, ALL_VERSIONS)
        addMavenArtifact(base, "com.example.test", "art-a", sortOrder = 0)
        // ADR-018 redesign: enumeration partitions supported (= ALL here) by value-change edges. A
        // non-ownership scalar override at [1.0.107,) introduces the edge 1.0.107 → two partitions
        // (,1.0.107) and [1.0.107,); neither has a per-range DISTRIBUTION_MAVEN/ownership override, so
        // both fall back to the component-level V1 wildcard.
        val jvOverride =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[1.0.107,)",
                overriddenAttribute = "build.javaVersion",
                rowType = "SCALAR_OVERRIDE",
            )

        comp.configurations.addAll(listOf(base, jvOverride))
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
        // One EXPLICIT mapping with ordered tokens (the new model's multi-artifact shape).
        addComponentLevelArtifact(comp, "com.example.test", "art-core,art-cli,art-xml,art-zeta,art-alpha")

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
        "RES-C-005: mixed — BASE+fallback, MARKER for range B only → range B uses marker, range A uses fallback",
    )
    fun `RES-C-005 marker-gated override only for marked ranges`() {
        // Demonstrates that the per-range MARKER gate is the discriminator:
        //   - range A (BASE, no marker)                  → wildcard from component-level fallback
        //   - range B (GROUP_ARTIFACT_PATTERN marker)    → marker-derived (groupPattern, artifactPattern)
        // V1-contract update (see RES-C-001): the legitimate per-range override marker
        // is GROUP_ARTIFACT_PATTERN; DISTRIBUTION_MAVEN no longer participates in
        // /maven-artifacts resolution.
        val comp = makeComponent("res-c-prime-fixture-mixed")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.test", V1_WILDCARD)

        val base = makeBase(comp, "(,1.0)")
        addMavenArtifact(base, "com.example.test", "art-x", sortOrder = 0)

        comp.addOwnershipMapping("com.example.new", "real-artifact-id", "[1.0,)")

        comp.configurations.add(base)
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
    // V1-contract preservation (RES-C-007, RES-C-008): when a per-range
    // DISTRIBUTION_MAVEN marker exists BUT the DSL did not redefine
    // groupId/artifactId at the version level, the V1 contract (see
    // component-resolver-core JiraParametersResolver.groovy:67-68) returns the
    // INHERITED top-level (artifactIdPattern, groupIdPattern) — never the
    // GAV-token-derived values. Reproduced today on prod + QA + local baseline
    // (curl 2026-05-24); the v3 DB-mode read-path returned GAV-derived strings
    // and these tests pin V1 parity.
    // ========================================================================

    @Test
    @DisplayName(
        "RES-C-007: 3-token top-level artifactId + version-level DISTRIBUTION_MAVEN-only override " +
            "preserves the inherited artifactPattern (V1 contract)",
    )
    fun `RES-C-007 DISTRIBUTION_MAVEN-only override preserves inherited artifactPattern`() {
        // DSL shape (mirrors a real 3-artifact-token component):
        //   "fixture-A" {
        //       groupId = "com.example.fx"
        //       artifactId = "art-a,art-b,art-c"          // inherited by every range
        //       "[4.9.4-4181,)" {
        //           distribution { gav = "...:lib-sdk:...×15" }
        //           // NOTE: artifactId is NOT redefined here — only the distribution.GAV
        //       }
        //   }
        //
        // Import writes:
        //   - component-level artifactIds row: artifactPattern="art-a,art-b,art-c"
        //   - DISTRIBUTION_MAVEN marker on [4.9.4-4181,) with 15× (com.example.fx, lib-sdk)
        //
        // V1 contract: /maven-artifacts returns the inherited artifactPattern, NOT the GAV-derived one.
        val comp = makeComponent("fixture-A-3-token")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example.fx", "art-a,art-b,art-c")

        // ADR-018 redesign: base is ALL_VERSIONS, supported coverage = the declared block [4.9.4-4181,)
        // as a RANGE_PRESENCE row; the DISTRIBUTION_MAVEN marker's range is a value-change edge.
        val base = makeBase(comp, ALL_VERSIONS)
        addMavenArtifact(base, "com.example.fx", "art-a", sortOrder = 0)
        addMavenArtifact(base, "com.example.fx", "art-b", sortOrder = 1)
        addMavenArtifact(base, "com.example.fx", "art-c", sortOrder = 2)
        val presence = makeRangePresenceRow(comp, "[4.9.4-4181,)")

        val distMaven = makeMarkerRow(comp, "[4.9.4-4181,)", MarkerAttributes.DISTRIBUTION_MAVEN)
        // 15 GAV tokens like the production DSL — all map to (com.example.fx, lib-sdk).
        repeat(15) { i -> addMavenArtifact(distMaven, "com.example.fx", "lib-sdk", sortOrder = i) }

        comp.configurations.addAll(listOf(base, presence, distMaven))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("fixture-A-3-token")

        val rangeWithOverride = result["[4.9.4-4181,)"]
        assertNotNull(rangeWithOverride, "[4.9.4-4181,) entry must be present")
        assertEquals(
            "com.example.fx",
            rangeWithOverride!!.groupPattern,
            "[4.9.4-4181,) groupPattern must be the inherited top-level — DISTRIBUTION_MAVEN does NOT override",
        )
        assertEquals(
            "art-a,art-b,art-c",
            rangeWithOverride.artifactPattern,
            "[4.9.4-4181,) artifactPattern must be the inherited top-level — DISTRIBUTION_MAVEN does NOT override",
        )
    }

    @Test
    @DisplayName(
        "RES-C-008: multi-element CSV groupId/artifactId at top-level, " +
            "per-range DISTRIBUTION_MAVEN override preserves the inherited CSV (V1 contract)",
    )
    fun `RES-C-008 DISTRIBUTION_MAVEN-only override on multi-element top-level preserves CSV`() {
        // DSL shape (mirrors a real two-element groupId CSV component):
        //   "fixture-B" {
        //       groupId = "com.example.fx.distrib,com.example.fx.distrib.installer"
        //       artifactId = "installer-art,main-art"
        //       "[1.7.3076,1.7.3209]" {
        //           distribution { gav = "com.example.fx.bundled:main-art:zip:tag1,...×8" }
        //       }
        //   }
        //
        // V1 contract: /maven-artifacts for the override range returns the inherited
        // CSV groupId AND CSV artifactId — NOT the single GAV-derived group nor the
        // 8-token repeated artifact list.
        val comp = makeComponent("fixture-B-csv")
        comp.distributionExplicit = true

        addComponentLevelArtifact(
            comp,
            "com.example.fx.distrib,com.example.fx.distrib.installer",
            "installer-art,main-art",
        )

        // ADR-018 redesign: base is ALL_VERSIONS, supported coverage = the declared block
        // [1.7.3076,1.7.3209] as a RANGE_PRESENCE row; the DISTRIBUTION_MAVEN marker is its edge.
        val base = makeBase(comp, ALL_VERSIONS)
        addMavenArtifact(
            base,
            "com.example.fx.distrib,com.example.fx.distrib.installer",
            "installer-art,main-art",
        )
        val presence = makeRangePresenceRow(comp, "[1.7.3076,1.7.3209]")

        val distMaven = makeMarkerRow(comp, "[1.7.3076,1.7.3209]", MarkerAttributes.DISTRIBUTION_MAVEN)
        // 8 GAV tokens like the production DSL — all map to (com.example.fx.bundled, main-art).
        repeat(8) { i ->
            addMavenArtifact(distMaven, "com.example.fx.bundled", "main-art", sortOrder = i)
        }

        comp.configurations.addAll(listOf(base, presence, distMaven))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("fixture-B-csv")

        val rangeWithOverride = result["[1.7.3076,1.7.3209]"]
        assertNotNull(rangeWithOverride, "[1.7.3076,1.7.3209] entry must be present")
        assertEquals(
            "com.example.fx.distrib,com.example.fx.distrib.installer",
            rangeWithOverride!!.groupPattern,
            "groupPattern must be the inherited 2-element CSV — DISTRIBUTION_MAVEN does NOT collapse it to single GAV groupId",
        )
        assertEquals(
            "installer-art,main-art",
            rangeWithOverride.artifactPattern,
            "artifactPattern must be the inherited CSV — DISTRIBUTION_MAVEN does NOT replace it with the GAV artifact-token list",
        )
    }

    @Test
    @DisplayName(
        "RES-C-009 (ADR-018 redesign): a per-range ownership split by an override edge keeps its " +
            "ownership on every partition sub-range (containment selection, NOT exact-match)",
    )
    fun `RES-C-009 ownership survives partition sub-ranges via containment`() {
        val comp = makeComponent("res-c-ownership-containment")
        comp.distributionExplicit = true

        val base = makeBase(comp, ALL_VERSIONS)
        // Per-range ownership at [2,8). A non-ownership scalar override at [4,6) introduces the edges
        // 4 and 6, so enumeration partitions the ownership range into [2,4),[4,6),[6,8) — none of which
        // EQUALS the stored "[2,8)". Exact-match ownership lookup would miss all three and drop them to
        // the (empty) base; containment must keep the [2,8) ownership on each sub-range (amendment C).
        comp.addOwnershipMapping("com.example.own", "widget", versionRange = "[2,8)")
        val edge =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[4,6)",
                overriddenAttribute = "build.javaVersion",
                rowType = "SCALAR_OVERRIDE",
            )
        comp.configurations.addAll(listOf(base, edge))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("res-c-ownership-containment")

        for (range in listOf("[2,4)", "[4,6)", "[6,8)")) {
            val entry = result[range]
            assertNotNull(entry, "$range must carry the containing [2,8) ownership; got keys=${result.keys}")
            assertEquals("com.example.own", entry!!.groupPattern, "$range groupPattern must come from the [2,8) ownership")
            assertEquals("widget", entry.artifactPattern, "$range artifactPattern must come from the [2,8) ownership")
        }
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

        comp.addOwnershipMapping("com.example.ic", "alpha-fixture", "[1.1,)")

        comp.configurations.add(base)
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

        addComponentLevelArtifact(comp, "com.example.widgets", "core-a")

        val base = makeBase(comp, "[1.0,)")
        addMavenArtifact(base, "com.example.widgets", "core-a")

        comp.addOwnershipMapping("com.example.widgets", "core-a,core-b,core-c", "[2.0,)")

        comp.configurations.add(base)
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("beta-fixture-mig047-csv")

        assertEquals(2, result.size, "Expected two range entries")
        val rangeOverride = result["[2.0,)"]
        assertNotNull(rangeOverride, "[2.0,) entry must be present")
        assertEquals("com.example.widgets", rangeOverride!!.groupPattern)
        assertEquals(
            "core-a,core-b,core-c",
            rangeOverride.artifactPattern,
            "[2.0,) artifactPattern must be CSV of all three GROUP_ARTIFACT_PATTERN children in sort order",
        )
    }

    @Test
    @DisplayName(
        "MIG-047-RES-003: when both DISTRIBUTION_MAVEN and GROUP_ARTIFACT_PATTERN markers " +
            "exist on the same versionRange, GROUP_ARTIFACT_PATTERN wins (DISTRIBUTION_MAVEN is ignored)",
    )
    fun `MIG-047-RES-003 same-range conflict — GROUP_ARTIFACT_PATTERN wins, DISTRIBUTION_MAVEN ignored`() {
        // V1-contract design (RES-C-007/008): DISTRIBUTION_MAVEN does NOT influence
        // /maven-artifacts at all — only GROUP_ARTIFACT_PATTERN does. So a range
        // carrying both markers must resolve to the GROUP_ARTIFACT_PATTERN coords,
        // not the DISTRIBUTION_MAVEN coords. The historical behaviour
        // (DISTRIBUTION_MAVEN winning) was the source of the v3 DB-mode regression
        // on 6 production components — see fix commit message for details.
        val comp = makeComponent("gamma-fixture-mig047-conflict")
        comp.distributionExplicit = true

        addComponentLevelArtifact(comp, "com.example", "gamma-fixture")

        val base = makeBase(comp, "[1.0,1.1)")
        addMavenArtifact(base, "com.example", "gamma-fixture")

        // Adversarial ordering preserved: DISTRIBUTION_MAVEN sits BEFORE
        // GROUP_ARTIFACT_PATTERN in the configurations list. With the V1-contract
        // fix the resolver filters DISTRIBUTION_MAVEN out of the marker set entirely,
        // so GROUP_ARTIFACT_PATTERN wins regardless of row-order.
        val distributionMaven = makeMarkerRow(comp, "[1.1,)", MarkerAttributes.DISTRIBUTION_MAVEN)
        addMavenArtifact(distributionMaven, "com.example.user-explicit", "gamma-fixture")

        // Per-range ownership override (replaces the old GROUP_ARTIFACT_PATTERN marker). The
        // DISTRIBUTION_MAVEN marker must NOT influence /maven-artifacts.
        comp.addOwnershipMapping("com.example.import-internal", "gamma-fixture", "[1.1,)")

        comp.configurations.addAll(listOf(base, distributionMaven))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("gamma-fixture-mig047-conflict")

        assertEquals(2, result.size, "Expected two range entries")

        val rangeOverride = result["[1.1,)"]
        assertNotNull(rangeOverride, "[1.1,) entry must be present")
        assertEquals(
            "com.example.import-internal",
            rangeOverride!!.groupPattern,
            "GROUP_ARTIFACT_PATTERN wins per V1 contract; DISTRIBUTION_MAVEN is invisible to /maven-artifacts",
        )
        assertEquals("gamma-fixture", rangeOverride.artifactPattern)
    }

    // ========================================================================
    // #357 Option A: forward /maven-artifacts renders ALL_EXCEPT_CLAIMED as the
    // sibling-aware anchored negative-lookahead (so the v1-v3 wire matches the
    // legacy DSL's exact-token exclusion byte-for-byte), NOT the bare catch-all.
    // Reverse find-by-artifact is unaffected (it keeps catch-all + specificity).
    // ========================================================================

    @Test
    @DisplayName(
        "RES-357-001: ALL_EXCEPT_CLAIMED forward render is the anchored lookahead over OTHER " +
            "components' EXPLICIT siblings on the same group/range",
    )
    fun `RES-357-001 getMavenArtifactParameters renders ALL_EXCEPT as anchored lookahead over explicit siblings`() {
        // owner owns the catch-all of com.example.alpha EXCEPT the artifact explicitly claimed by a
        // sibling component (claimed-model → EXPLICIT[claimed-model]). The forward /maven-artifacts
        // pattern must be the exact-token lookahead, mirroring the legacy DSL.
        val owner = makeComponent("payment-gateway-fixture")
        owner.addAllExceptMapping("com.example.alpha")
        owner.configurations.add(makeBase(owner, ALL_VERSIONS))

        val sibling = makeComponent("claimed-model-fixture")
        sibling.addOwnershipMapping("com.example.alpha", "claimed-model")

        `when`(componentRepository.findByComponentKey("payment-gateway-fixture")).thenReturn(owner)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(owner, sibling))

        val result = resolver.getMavenArtifactParameters("payment-gateway-fixture")

        assertEquals(1, result.size, "Expected one range entry for ALL_VERSIONS")
        val entry = result[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals("com.example.alpha", entry!!.groupPattern)
        assertEquals(
            "(?!(?:claimed-model)\$)[\\w-\\.]+",
            entry.artifactPattern,
            "ALL_EXCEPT forward pattern must be the anchored exact-token lookahead over the sibling",
        )
    }

    @Test
    @DisplayName(
        "RES-357-002: ALL_EXCEPT_CLAIMED with NO sibling EXPLICIT claim degrades to the plain catch-all",
    )
    fun `RES-357-002 ALL_EXCEPT with no siblings renders the plain catch-all`() {
        val owner = makeComponent("sole-owner-fixture")
        owner.addAllExceptMapping("com.example.lonely")
        owner.configurations.add(makeBase(owner, ALL_VERSIONS))

        `when`(componentRepository.findByComponentKey("sole-owner-fixture")).thenReturn(owner)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(owner))

        val result = resolver.getMavenArtifactParameters("sole-owner-fixture")
        val entry = result[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals(
            "[\\w-\\.]+",
            entry!!.artifactPattern,
            "with no excluded sibling there is nothing to exclude — degrade to the catch-all",
        )
    }

    @Test
    @DisplayName(
        "RES-357-003: a sibling EXPLICIT claim in a DIFFERENT group does NOT narrow the ALL_EXCEPT pattern",
    )
    fun `RES-357-003 ALL_EXCEPT ignores explicit claims on a different group`() {
        val owner = makeComponent("group-scoped-owner")
        owner.addAllExceptMapping("com.example.alpha")
        owner.configurations.add(makeBase(owner, ALL_VERSIONS))

        // EXPLICIT claim, but on a different group — must not appear in the lookahead.
        val sibling = makeComponent("other-group-sibling")
        sibling.addOwnershipMapping("com.example.other", "unrelated-art")

        `when`(componentRepository.findByComponentKey("group-scoped-owner")).thenReturn(owner)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(owner, sibling))

        val result = resolver.getMavenArtifactParameters("group-scoped-owner")
        assertEquals(
            "[\\w-\\.]+",
            result[ALL_VERSIONS]!!.artifactPattern,
            "an EXPLICIT claim on a different group must not narrow this group's ALL_EXCEPT pattern",
        )
    }

    @Test
    @DisplayName(
        "RES-357-004: a same-group EXPLICIT sibling in a NON-intersecting range does NOT narrow the " +
            "ALL_EXCEPT pattern (the range gate excludes it) → plain catch-all",
    )
    fun `RES-357-004 ALL_EXCEPT ignores explicit siblings in a disjoint version range`() {
        // Owner's ALL_EXCEPT is an override mapping scoped to [1.0,2.0); the sibling EXPLICIT claims
        // the SAME group but in the disjoint [5.0,6.0). rangesIntersect is false → the sibling is not
        // in force for this range → nothing to exclude → catch-all.
        val owner = makeComponent("range-scoped-owner")
        owner.addAllExceptMapping("com.example.alpha", "[1.0,2.0)")
        owner.configurations.add(makeBase(owner, "[1.0,2.0)"))

        val sibling = makeComponent("future-range-sibling")
        sibling.addOwnershipMapping("com.example.alpha", "claimed-model", "[5.0,6.0)")

        `when`(componentRepository.findByComponentKey("range-scoped-owner")).thenReturn(owner)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(owner, sibling))

        val result = resolver.getMavenArtifactParameters("range-scoped-owner")
        assertEquals(
            "[\\w-\\.]+",
            result["[1.0,2.0)"]!!.artifactPattern,
            "a sibling in a non-intersecting range must not appear in the ALL_EXCEPT lookahead",
        )
    }

    @Test
    @DisplayName(
        "RES-357-005: a component with config rows but NO ownership mappings yields no /maven-artifacts " +
            "entries (must not NPE on the empty effective-mapping list → 500)",
    )
    fun `RES-357-005 component with no artifact ownership mappings yields an empty map`() {
        val comp = makeComponent("no-ownership-fixture")
        // A base config row, but artifactMappings stays empty (create allows an empty artifactIds list).
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))
        stubComponent(comp)

        val result = resolver.getMavenArtifactParameters("no-ownership-fixture")
        assertEquals(0, result.size, "no ownership mappings → no entries (and crucially, no 500)")
    }

    @Test
    @DisplayName(
        "RES-357-006: a rival's BASE EXPLICIT token is NOT excluded in a range the rival itself overrides " +
            "(override REPLACES base → the base token is not in force there)",
    )
    fun `RES-357-006 ALL_EXCEPT skips a rival base token shadowed by the rival's own override`() {
        // Owner: ALL_EXCEPT on com.example.alpha for the override range [1.0,2.0).
        val owner = makeComponent("shadow-owner")
        owner.addAllExceptMapping("com.example.alpha", "[1.0,2.0)")
        owner.configurations.add(makeBase(owner, "[1.0,2.0)"))

        // Rival: base EXPLICIT[base-art] on the SAME group, AND its OWN override EXPLICIT[override-art]
        // for [1.0,2.0). In [1.0,2.0) the rival's base is replaced by its override → base-art is not in
        // force, so the owner's ALL_EXCEPT must exclude only override-art (not base-art).
        val rival = makeComponent("shadow-rival")
        rival.addOwnershipMapping("com.example.alpha", "base-art")
        rival.addOwnershipMapping("com.example.alpha", "override-art", "[1.0,2.0)")

        `when`(componentRepository.findByComponentKey("shadow-owner")).thenReturn(owner)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(owner, rival))

        val result = resolver.getMavenArtifactParameters("shadow-owner")
        assertEquals(
            "(?!(?:override-art)\$)[\\w-\\.]+",
            result["[1.0,2.0)"]!!.artifactPattern,
            "the rival's base token is shadowed by its own [1.0,2.0) override → exclude only override-art",
        )
    }

    // ========================================================================
    // ARTGRP: artifact-group canonicalization (one groupId per stored row).
    //
    // Storage is normalized so `group_pattern` holds exactly ONE Maven groupId: a
    // legacy comma group-list `"a,b"` is split at import/write into one mapping per
    // group with the SAME mode/tokens/range. The legacy v1-v3 forward wire MUST stay
    // byte-identical, so the render RE-COMPOSES contiguous split-equivalent rows back
    // into the single `(groupIdPattern, artifactIdPattern)` pair (`"a,b"`). These
    // guards pin: (1) re-compose does not drop a group; (2) it does NOT over-join
    // genuinely-distinct same-range mappings (different tokens / ALL_EXCEPT); (3) an
    // un-migrated single CSV row still renders identically.
    // ========================================================================

    @Test
    @DisplayName(
        "ARTGRP-001: two split EXPLICIT rows (one groupId each, same token) re-compose into the " +
            "legacy CSV groupPattern on /maven-artifacts — no group dropped",
    )
    fun `ARTGRP-001 split explicit group rows recompose to csv wire`() {
        val comp = makeComponent("artgrp-split-explicit")
        comp.distributionExplicit = true
        // Two SEPARATE per-groupId rows sharing mode+token (the post-canonicalization storage shape —
        // and the identical shape whether they came from splitting one "a,b" pair or from two
        // independently-authored v4 rows). Both re-compose: "a,b"→X ≡ {a→X, b→X}, so the merge is
        // semantically lossless and NOT provenance-dependent.
        comp.addOwnershipMapping("grp-alfa", "widget")
        comp.addOwnershipMapping("grp-beta", "widget")
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))
        stubComponent(comp)

        val entry = resolver.getMavenArtifactParameters("artgrp-split-explicit")[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals(
            "grp-alfa,grp-beta",
            entry!!.groupPattern,
            "split rows must re-compose to the legacy CSV group — the primary-only render must NOT drop grp-beta",
        )
        assertEquals("widget", entry.artifactPattern, "artifact pattern is the shared token")
    }

    @Test
    @DisplayName(
        "ARTGRP-002 (no over-join): two same-range EXPLICIT rows with DIFFERENT tokens are NOT merged — " +
            "the legacy single pair renders only the primary (lowest sortOrder), exactly as today",
    )
    fun `ARTGRP-002 heterogeneous same-range explicit rows are not over-joined`() {
        val comp = makeComponent("artgrp-heterogeneous")
        comp.distributionExplicit = true
        comp.addOwnershipMapping("grp-alfa", "widget-x")
        comp.addOwnershipMapping("grp-beta", "widget-y")
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))
        stubComponent(comp)

        val entry = resolver.getMavenArtifactParameters("artgrp-heterogeneous")[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals(
            "grp-alfa",
            entry!!.groupPattern,
            "distinct-token rows are NOT split-equivalent → must not merge into 'grp-alfa,grp-beta'",
        )
        assertEquals("widget-x", entry.artifactPattern, "only the primary row's token is rendered")
    }

    @Test
    @DisplayName(
        "ARTGRP-003 (ALL_EXCEPT non-collapse): two single-group ALL_EXCEPT_CLAIMED rows are NEVER merged " +
            "(their forward pattern is per-group sibling-aware) — the primary renders alone",
    )
    fun `ARTGRP-003 all-except rows never collapse`() {
        val comp = makeComponent("artgrp-all-except")
        comp.addAllExceptMapping("grp-alfa")
        comp.addAllExceptMapping("grp-beta")
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))
        stubComponent(comp)

        val entry = resolver.getMavenArtifactParameters("artgrp-all-except")[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals(
            "grp-alfa",
            entry!!.groupPattern,
            "ALL_EXCEPT_CLAIMED rows must each stay their own pair — the primary renders alone, never 'grp-alfa,grp-beta'",
        )
    }

    @Test
    @DisplayName(
        "ARTGRP-004 (back-compat): an un-migrated single CSV-group row still renders the same CSV wire " +
            "(the re-compose helper is a no-op on a one-row legacy pair)",
    )
    fun `ARTGRP-004 legacy single csv group row renders unchanged`() {
        val comp = makeComponent("artgrp-legacy-csv")
        comp.distributionExplicit = true
        comp.addOwnershipMapping("grp-alfa,grp-beta", "widget")
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))
        stubComponent(comp)

        val entry = resolver.getMavenArtifactParameters("artgrp-legacy-csv")[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals("grp-alfa,grp-beta", entry!!.groupPattern, "a legacy single CSV row must keep rendering the CSV verbatim")
        assertEquals("widget", entry.artifactPattern)
    }

    @Test
    @DisplayName(
        "ARTGRP-005 (ALL mode merge): two same-range ALL rows for different groups re-compose into the " +
            "catch-all pair with the CSV group — 'own everything under a' + 'under b' ≡ 'under a,b'",
    )
    fun `ARTGRP-005 all-mode rows for different groups recompose`() {
        val comp = makeComponent("artgrp-all-mode")
        comp.distributionExplicit = true
        // ALL mode = catch-all artifact pattern; two groups. Merging is lossless (same catch-all).
        comp.addOwnershipMapping("grp-alfa", V1_WILDCARD)
        comp.addOwnershipMapping("grp-beta", V1_WILDCARD)
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))
        stubComponent(comp)

        val entry = resolver.getMavenArtifactParameters("artgrp-all-mode")[ALL_VERSIONS]
        assertNotNull(entry, "ALL_VERSIONS entry must be present")
        assertEquals("grp-alfa,grp-beta", entry!!.groupPattern, "ALL rows for different groups re-compose to the CSV group")
        assertEquals(V1_WILDCARD, entry.artifactPattern, "shared catch-all pattern is unchanged")
    }

    @Test
    @DisplayName(
        "ARTGRP-ESC-001: split EXPLICIT rows re-compose into the legacy groupIdPattern of the escrow " +
            "module view (drives /maven-artifacts, view-as-code and the v1-v3 compat baseline)",
    )
    fun `ARTGRP-ESC-001 split rows recompose escrow groupIdPattern`() {
        val comp = makeComponent("artgrp-escrow")
        comp.addOwnershipMapping("grp-alfa", "widget")
        comp.addOwnershipMapping("grp-beta", "widget")
        comp.configurations.add(makeBase(comp, ALL_VERSIONS))

        val module = comp.toEscrowModule(versionRangeFactory, numericVersionFactory)
        val cfg = module.moduleConfigurations.first()
        assertEquals(
            "grp-alfa,grp-beta",
            cfg.groupIdPattern,
            "escrow groupIdPattern must re-compose the split rows — must NOT drop grp-beta",
        )
        assertEquals("widget", cfg.artifactIdPattern, "artifactIdPattern is the shared token")
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
