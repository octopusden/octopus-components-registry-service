package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentDocLinkEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionFileUrlArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * Unit tests for [DatabaseComponentRegistryResolver] against the v2 entity graph (Model A').
 *
 * All tests build in-memory entity fixtures (no Spring context, no DB) and mock only
 * [ComponentRepository] + [DependencyMappingRepository]. The heavy lifting happens inside
 * `EntityMappers.toEscrowModule` / `toResolvedEscrowModuleConfig`, which this resolver
 * delegates to.
 *
 * Scenarios covered (see task Phase-6 ledger):
 *   (1)  Pure base row
 *   (2)  Scalar override at a version range
 *   (3)  Marker override — vcs.settings
 *   (4)  Marker override — distribution.maven
 *   (5)  Synthetic base (MIG-029)
 *   (6)  Doc-link resolution
 *   (7)  Distribution GAV concatenation (maven + file-url, sort-order)
 *   (8)  Build required-tools marker
 *
 * Note on doc-link major-version matching: the `pickDocLink` implementation extracts the
 * first digit sequence from the versionRange string via `Regex("(\\d+)")`. For a range
 * like `[1.0,2.0)` this yields `"1"` (not `"1.0"`). Tests therefore use `majorVersion = "1"` in
 * the fixture, NOT `"1.0"` — the two are distinct strings and the latter would NOT match.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class DatabaseComponentRegistryResolverTest {

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
    // Entity factory helpers
    // ========================================================================

    private fun makeComponent(key: String): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    /**
     * Creates a BASE configuration row (overriddenAttribute = null).
     *
     * **`deprecated = false` is mandatory here.**
     * `EscrowModuleConfig.deprecated` is a primitive `boolean` field, and
     * `EntityMappers.setField` does not guard against null-to-primitive assignment.
     * Leaving `deprecated = null` (the entity default) causes an
     * `IllegalArgumentException` at runtime when the mapper calls
     * `field.set(config, null)` on the primitive field.
     *
     * // FIXME(Phase 6 review): EntityMappers.setField should use `merged.deprecated ?: false`
     * //   (or catch IllegalArgumentException) so that components with no deprecated column
     * //   value survive toEscrowModule / toResolvedEscrowModuleConfig without crashing.
     */
    private fun makeBase(
        component: ComponentEntity,
        versionRange: String = ALL_VERSIONS,
        buildSystem: String? = null,
        javaVersion: String? = null,
        jiraProjectKey: String? = null,
        isSyntheticBase: Boolean = false,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = null,
            rowType = "BASE",
            isSyntheticBase = isSyntheticBase,
            buildSystem = buildSystem,
            javaVersion = javaVersion,
            jiraProjectKey = jiraProjectKey,
            deprecated = false,   // must be non-null; see KDoc above
        )

    /**
     * Creates a SCALAR OVERRIDE row with the given attribute path.
     * Caller must set the single typed column (e.g. `row.javaVersion = "21"`) after creation.
     */
    private fun makeScalarOverrideRow(
        component: ComponentEntity,
        versionRange: String,
        attribute: String,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = attribute,
            rowType = "SCALAR_OVERRIDE",
        )

    /**
     * Creates a MARKER row (child-collection replacement override).
     * Caller populates the appropriate child collection (vcsEntries, mavenArtifacts, etc.).
     */
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

    /** RANGE_PRESENCE coverage anchor (ADR-018): NULL overridden_attribute, all typed cols NULL. */
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

    private fun makeVcsEntry(
        config: ComponentConfigurationEntity,
        vcsPath: String,
        name: String = "main",
        branch: String? = "master",
        sortOrder: Int = 0,
    ): VcsSettingsEntryEntity =
        VcsSettingsEntryEntity(
            componentConfiguration = config,
            vcsPath = vcsPath,
            name = name,
            branch = branch,
            sortOrder = sortOrder,
        )

    private fun makeMavenArtifact(
        config: ComponentConfigurationEntity,
        groupPattern: String,
        artifactPattern: String,
        sortOrder: Int = 0,
    ): DistributionMavenArtifactEntity =
        DistributionMavenArtifactEntity(
            componentConfiguration = config,
            groupPattern = groupPattern,
            artifactPattern = artifactPattern,
            sortOrder = sortOrder,
        )

    private fun makeFileUrlArtifact(
        config: ComponentConfigurationEntity,
        url: String,
        sortOrder: Int = 0,
    ): DistributionFileUrlArtifactEntity =
        DistributionFileUrlArtifactEntity(
            componentConfiguration = config,
            url = url,
            sortOrder = sortOrder,
        )

    private fun makeDocLink(
        component: ComponentEntity,
        docComponentKey: String,
        majorVersion: String? = null,
        sortOrder: Int = 0,
    ): ComponentDocLinkEntity =
        ComponentDocLinkEntity(
            component = component,
            docComponentKey = docComponentKey,
            majorVersion = majorVersion,
            sortOrder = sortOrder,
        )

    /**
     * Creates a [ComponentRequiredToolEntity] junction pointing at a [ToolEntity] with
     * the given name. The `tool` field is set directly so the mapper can read it without
     * a Hibernate session.
     */
    private fun makeToolJunction(toolName: String): ComponentRequiredToolEntity =
        ComponentRequiredToolEntity(
            toolName = toolName,
            tool = ToolEntity(name = toolName),
        )

    /** Wire Mockito to return [component] for both key-lookup and findAll. */
    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    // ========================================================================
    // (1) Pure base row
    // ========================================================================

    @Test
    fun `(1) pure base row - enumeration yields one EscrowModuleConfig with base scalars`() {
        val comp = makeComponent("COMP1")
        val base = makeBase(comp, javaVersion = "11", buildSystem = "MAVEN")
        base.vcsEntries.add(makeVcsEntry(base, "ssh://vcs/comp1"))
        comp.configurations.add(base)
        stubComponent(comp)

        val module = resolver.getComponentById("COMP1")
        assertNotNull(module)
        assertEquals(1, module!!.moduleConfigurations.size)
        val cfg = module.moduleConfigurations.first()
        assertEquals("11", cfg.buildConfiguration?.javaVersion)
        assertNotNull(cfg.vcsSettings)
        assertEquals(1, cfg.vcsSettings!!.versionControlSystemRoots.size)
        assertEquals("ssh://vcs/comp1", cfg.vcsSettings!!.versionControlSystemRoots.first().vcsPath)
    }

    @Test
    fun `(1b) pure base row - resolve any version returns base javaVersion`() {
        val comp = makeComponent("COMP1B")
        val base = makeBase(comp, javaVersion = "11")
        comp.configurations.add(base)
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP1B", "3.0.0")
        assertNotNull(cfg)
        assertEquals("11", cfg!!.buildConfiguration?.javaVersion)
    }

    // ========================================================================
    // (2) Scalar override at a single version range
    // ========================================================================

    @Test
    fun `(2) scalar override on supported=ALL - enumeration partitions ALL by the override edges`() {
        val comp = makeComponent("COMP2")
        val base = makeBase(comp, javaVersion = "11")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "21"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        // ADR-018 redesign: supported = ALL (no RANGE_PRESENCE) is partitioned by the override's
        // value-change edges (1.0, 2.0) → three constant-value views, no overlapping ALL view.
        val module = resolver.getComponentById("COMP2")
        assertNotNull(module)
        assertEquals(
            listOf("(,1.0)", "[1.0,2.0)", "[2.0,)"),
            module!!.moduleConfigurations.map { it.versionRangeString },
        )
        assertEquals("11", module.moduleConfigurations.first { it.versionRangeString == "(,1.0)" }.buildConfiguration?.javaVersion)
        assertEquals("21", module.moduleConfigurations.first { it.versionRangeString == "[1.0,2.0)" }.buildConfiguration?.javaVersion)
        assertEquals("11", module.moduleConfigurations.first { it.versionRangeString == "[2.0,)" }.buildConfiguration?.javaVersion)
    }

    @Test
    fun `(2b) scalar override - resolve 1·5·0 returns overridden javaVersion 21`() {
        val comp = makeComponent("COMP2B")
        val base = makeBase(comp, javaVersion = "11")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "21"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP2B", "1.5.0")
        assertNotNull(cfg)
        assertEquals("21", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    fun `(2c) scalar override - resolve 2·0·0 returns base javaVersion 11`() {
        val comp = makeComponent("COMP2C")
        val base = makeBase(comp, javaVersion = "11")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "21"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        // 2.0.0 is the exclusive upper bound of [1.0,2.0) — override does NOT apply
        val cfg = resolver.getResolvedComponentDefinition("COMP2C", "2.0.0")
        assertNotNull(cfg)
        assertEquals("11", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    fun `(5e4 MIG-029) singleton + gap vcs markers enumerate as three distinct-vcs views, not one merged`() {
        // Reproduces the live multi-marker shape (confirmed via QA DB): coverage [2.6.145,2.6.179] with a
        // singleton vcs marker at each end and a DISTINCT vcs marker on the open gap between them — three
        // different vcs paths. V1 enumerates three views; the decoupled-model partition must split on the
        // marker edges {2.6.145, 2.6.179} and resolve each sub-range to its own marker, NOT collapse them
        // into one view (which would silently drop the per-range vcs distinction the reference keeps).
        val comp = makeComponent("COMP5E4")
        val base = makeBase(comp).apply { createdAt = java.time.Instant.ofEpochMilli(1000) }
        base.vcsEntries.add(makeVcsEntry(base, "ssh://vcs/base"))
        val presence = makeRangePresenceRow(comp, "[2.6.145,2.6.179]").apply { createdAt = java.time.Instant.ofEpochMilli(2000) }
        val mLow = makeMarkerRow(comp, "[2.6.145]", "vcs.settings").apply { createdAt = java.time.Instant.ofEpochMilli(3000) }
        mLow.vcsEntries.add(makeVcsEntry(mLow, "ssh://vcs/low"))
        val mGap = makeMarkerRow(comp, "(2.6.145,2.6.179)", "vcs.settings").apply { createdAt = java.time.Instant.ofEpochMilli(4000) }
        mGap.vcsEntries.add(makeVcsEntry(mGap, "ssh://vcs/gap"))
        val mHigh = makeMarkerRow(comp, "[2.6.179]", "vcs.settings").apply { createdAt = java.time.Instant.ofEpochMilli(5000) }
        mHigh.vcsEntries.add(makeVcsEntry(mHigh, "ssh://vcs/high"))
        comp.configurations.addAll(listOf(base, presence, mLow, mGap, mHigh))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5E4")
        assertNotNull(module)
        assertEquals(
            listOf("[2.6.145]", "(2.6.145,2.6.179)", "[2.6.179]"),
            module!!.moduleConfigurations.map { it.versionRangeString },
            "the three marker edges must produce three enumerated views",
        )
        val vcsOf = { i: Int -> module.moduleConfigurations[i].vcsSettings!!.versionControlSystemRoots.first().vcsPath }
        assertEquals("ssh://vcs/low", vcsOf(0))
        assertEquals("ssh://vcs/gap", vcsOf(1))
        assertEquals("ssh://vcs/high", vcsOf(2))
    }

    // ========================================================================
    // (3) Marker override: vcs.settings
    // ========================================================================

    @Test
    fun `(3) marker vcs-settings - resolve 1·5·0 gets three-root VCS from override marker`() {
        val comp = makeComponent("COMP3")
        val base = makeBase(comp, javaVersion = "8")
        base.vcsEntries.add(makeVcsEntry(base, "ssh://vcs/comp3"))
        val markerRow = makeMarkerRow(comp, "[1.0,2.0)", "vcs.settings")
        markerRow.vcsEntries.addAll(
            listOf(
                makeVcsEntry(markerRow, "ssh://vcs/comp3-a", name = "fe", sortOrder = 0),
                makeVcsEntry(markerRow, "ssh://vcs/comp3-b", name = "be", sortOrder = 1),
                makeVcsEntry(markerRow, "ssh://vcs/comp3-c", name = "common", sortOrder = 2),
            ),
        )
        comp.configurations.addAll(listOf(base, markerRow))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP3", "1.5.0")
        assertNotNull(cfg)
        assertNotNull(cfg!!.vcsSettings)
        val roots = cfg.vcsSettings!!.versionControlSystemRoots
        assertEquals(3, roots.size)
        val paths = roots.map { it.vcsPath }.toSet()
        assertTrue("ssh://vcs/comp3-a" in paths)
        assertTrue("ssh://vcs/comp3-b" in paths)
        assertTrue("ssh://vcs/comp3-c" in paths)
    }

    @Test
    fun `(3b) marker vcs-settings - resolve 0·9·0 gets single-root VCS from base`() {
        val comp = makeComponent("COMP3B")
        val base = makeBase(comp, javaVersion = "8")
        base.vcsEntries.add(makeVcsEntry(base, "ssh://vcs/comp3b"))
        val markerRow = makeMarkerRow(comp, "[1.0,2.0)", "vcs.settings")
        markerRow.vcsEntries.addAll(
            listOf(
                makeVcsEntry(markerRow, "ssh://vcs/comp3b-x", name = "x"),
                makeVcsEntry(markerRow, "ssh://vcs/comp3b-y", name = "y"),
                makeVcsEntry(markerRow, "ssh://vcs/comp3b-z", name = "z"),
            ),
        )
        comp.configurations.addAll(listOf(base, markerRow))
        stubComponent(comp)

        // 0.9.0 is outside [1.0,2.0) → override does not apply → base single VCS used
        val cfg = resolver.getResolvedComponentDefinition("COMP3B", "0.9.0")
        assertNotNull(cfg)
        assertNotNull(cfg!!.vcsSettings)
        assertEquals(1, cfg.vcsSettings!!.versionControlSystemRoots.size)
        assertEquals("ssh://vcs/comp3b", cfg.vcsSettings!!.versionControlSystemRoots.first().vcsPath)
    }

    // ========================================================================
    // (4) Marker override: distribution.maven
    // ========================================================================

    @Test
    fun `(4) marker distribution-maven - resolve 1·5·0 returns override single artifact`() {
        val comp = makeComponent("COMP4")
        comp.distributionExplicit = true
        val base = makeBase(comp)
        base.mavenArtifacts.addAll(
            listOf(
                makeMavenArtifact(base, "com.example", "lib-core", sortOrder = 0),
                makeMavenArtifact(base, "com.example", "lib-extra", sortOrder = 1),
            ),
        )
        val markerRow = makeMarkerRow(comp, "[1.0,2.0)", "distribution.maven")
        markerRow.mavenArtifacts.add(makeMavenArtifact(markerRow, "com.example", "lib-v2"))
        comp.configurations.addAll(listOf(base, markerRow))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP4", "1.5.0")
        assertNotNull(cfg)
        assertNotNull(cfg!!.distribution)
        assertEquals("com.example:lib-v2", cfg.distribution!!.GAV())
    }

    @Test
    fun `(4b) marker distribution-maven - resolve 0·9·0 returns base two-artifact GAV`() {
        val comp = makeComponent("COMP4B")
        comp.distributionExplicit = true
        val base = makeBase(comp)
        base.mavenArtifacts.addAll(
            listOf(
                makeMavenArtifact(base, "com.example", "lib-core", sortOrder = 0),
                makeMavenArtifact(base, "com.example", "lib-extra", sortOrder = 1),
            ),
        )
        val markerRow = makeMarkerRow(comp, "[1.0,2.0)", "distribution.maven")
        markerRow.mavenArtifacts.add(makeMavenArtifact(markerRow, "com.example", "lib-v2"))
        comp.configurations.addAll(listOf(base, markerRow))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP4B", "0.9.0")
        assertNotNull(cfg)
        assertNotNull(cfg!!.distribution)
        assertEquals("com.example:lib-core,com.example:lib-extra", cfg.distribution!!.GAV())
    }

    // ========================================================================
    // (5) Synthetic base — MIG-029
    // ========================================================================

    @Test
    fun `(5 MIG-029) decoupled base with override - enumeration emits only the declared range`() {
        // ADR-018: a version-range-only component migrates to an ALL_VERSIONS base
        // (the default carrier) + a RANGE_PRESENCE row per declared block. The
        // all-versions base is NOT a view of its own when presence rows exist, so
        // enumeration emits exactly the declared range.
        val comp = makeComponent("COMP5")
        val base = makeBase(comp, javaVersion = "8")
        val presence = makeRangePresenceRow(comp, "[1.0,2.0)")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, presence, overrideRow))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5")
        assertNotNull(module)
        // ALL_VERSIONS base + RANGE_PRESENCE → base view suppressed; only the declared range
        assertEquals(1, module!!.moduleConfigurations.size)
        assertEquals("[1.0,2.0)", module.moduleConfigurations.first().versionRangeString)
    }

    @Test
    fun `(5b MIG-029) decoupled base - resolve 1·5·0 returns overridden javaVersion 11`() {
        val comp = makeComponent("COMP5B")
        val base = makeBase(comp, javaVersion = "8")
        val presence = makeRangePresenceRow(comp, "[1.0,2.0)")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, presence, overrideRow))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP5B", "1.5.0")
        assertNotNull(cfg)
        assertEquals("11", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    fun `(5c MIG-029) decoupled base - resolve 0·9·0 outside supported returns null (coverage gate)`() {
        val comp = makeComponent("COMP5C")
        val base = makeBase(comp, javaVersion = "8")
        val presence = makeRangePresenceRow(comp, "[1.0,2.0)")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, presence, overrideRow))
        stubComponent(comp)

        // 0.9.0 is outside supported = ∪ RANGE_PRESENCE ([1.0,2.0)). The ALL_VERSIONS base is the
        // default carrier, NOT coverage — so the coverage gate returns null (V1 404), matching the
        // real migration of a bounded-block-only component (the old synthetic-bounded base 404'd here too).
        assertNull(resolver.getResolvedComponentDefinition("COMP5C", "0.9.0"))
    }

    @Test
    fun `(5d MIG-029) ALL_VERSIONS base with no RANGE_PRESENCE - enumeration emits the base range`() {
        val comp = makeComponent("COMP5D")
        // supported = ALL (no RANGE_PRESENCE rows) → the ALL_VERSIONS base IS a view of its own and
        // must be enumerated (the M1 / top-level-only shape). isSyntheticBase is vestigial under
        // ADR-018 (always false in real data); set here only to exercise the mapper's legacy guard.
        val base = makeBase(comp, isSyntheticBase = true, javaVersion = "8")
        comp.configurations.add(base)
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5D")
        assertNotNull(module)
        // No RANGE_PRESENCE rows → base view NOT suppressed
        assertEquals(1, module!!.moduleConfigurations.size)
        assertEquals(ALL_VERSIONS, module.moduleConfigurations.first().versionRangeString)
    }

    @Test
    fun `(5e MIG-029) synthetic base with multiple overrides - first config follows DSL declaration order (createdAt), not heap-scan`() {
        // Models the MANUAL-below-supported scenario: synthetic base (AUTO via Defaults),
        // supported coverage = [1.0,) only, and a MANUAL escrow.generation override on the
        // OUT-OF-SUPPORTED tail (,1.0). Because the override is outside coverage it must NOT
        // be enumerated, so moduleConfigurations[0] inherits AUTO from the base.
        //
        // NOTE: this is the INVERSE of the real wscardsmodel shape (see 5e2 below), where the
        // base escrow is MANUAL and AUTO sits on higher value-change islands → [0] = MANUAL.
        // Kept as a distinct guard for the out-of-coverage-override suppression path.
        //
        // V1 contract (prod = QA = baseline): the controller's createComponent() picks
        // moduleConfigurations[0], which V1 emits in DSL declaration order. For an archived
        // component whose first DSL range has no escrow{} block, that's AUTO.
        //
        // v3 DB-mode used to iterate Hibernate @OneToMany configurations in DB heap-scan
        // order — non-deterministic — so moduleConfigurations[0] could become the MANUAL
        // override range, leaking MANUAL into the wire response and producing 6 stable
        // diffs on id17 #3662 for wscardsmodel across `/v2/components/{c}`, list endpoints
        // and `/v3/components`.
        //
        // The fixture below inserts rows in an ADVERSARIAL order (MANUAL override BEFORE
        // the DSL-first RANGE_PRESENCE row) but assigns createdAt timestamps that reflect
        // DSL declaration order. The fix in EntityMappers.toEscrowModule sorts configs by
        // (rowType != "BASE", createdAt, id) before enumeration, which restores the V1
        // contract: moduleConfigurations[0] is the DSL-first range (createdAt-min), not
        // the adversarially-inserted MANUAL range.
        val comp = makeComponent("COMP5E")
        val base = makeBase(comp, isSyntheticBase = true).apply {
            escrowGeneration = "AUTO"
            createdAt = java.time.Instant.ofEpochMilli(1000)
        }
        // DSL-first override range — RANGE_PRESENCE only, no escrow.generation override.
        // resolveForRange inherits escrow_generation from the synthetic base → AUTO.
        val rangePresenceForAutoRange = ComponentConfigurationEntity(
            component = comp,
            versionRange = "[1.0,)",
            overriddenAttribute = null,
            rowType = "RANGE_PRESENCE",
        ).apply { createdAt = java.time.Instant.ofEpochMilli(2000) }
        // DSL-second override range — SCALAR_OVERRIDE for escrow.generation = MANUAL.
        val manualOverride = makeScalarOverrideRow(comp, "(,1.0)", "escrow.generation").apply {
            escrowGeneration = "MANUAL"
            createdAt = java.time.Instant.ofEpochMilli(3000)
        }
        // Insertion order DELIBERATELY adversarial: MANUAL row before RANGE_PRESENCE.
        // Pre-fix Hibernate could surface this same order on real Postgres heap-scan;
        // post-fix the createdAt-based sort restores DSL order before enumeration.
        comp.configurations.addAll(listOf(base, manualOverride, rangePresenceForAutoRange))
        stubComponent(comp)

        // ADR-018 redesign: supported = the RANGE_PRESENCE coverage = [1.0,). The MANUAL override on
        // (,1.0) is OUTSIDE supported, so it is NOT enumerated (and cannot leak MANUAL into the wire) —
        // a stronger guarantee than the old createdAt-ordering fix. Enumeration is version-ordered.
        val module = resolver.getComponentById("COMP5E")
        assertNotNull(module)
        assertEquals(listOf("[1.0,)"), module!!.moduleConfigurations.map { it.versionRangeString })
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.AUTO,
            module.moduleConfigurations.first().escrow!!.generation.orElse(null),
            "the only supported range inherits AUTO from base; the out-of-supported MANUAL override does not appear",
        )
    }

    @Test
    fun `(5e2 MIG-029) real wscardsmodel shape - MANUAL base with AUTO islands, first view stays MANUAL`() {
        // Faithful to the REAL migrated wscardsmodel (verified by migrating the live DSL through
        // the real loader into ft-db H2 and reading the rows): the component's escrow default is
        // MANUAL (ESCROW_PROVIDED_MANUALLY base), and the GRADLE/AUTO releases are value-change
        // ISLANDS on higher ranges. The compat stand previously showed V1=MANUAL vs candidate=AUTO
        // for moduleConfigurations[0]; the live migration on the current tip produces
        //   [0] (,2.1) = MANUAL  (matching V1)
        // because [0] is the lowest sub-range, which inherits the MANUAL base — NOT the AUTO island.
        //
        // This locks that contract in: an AUTO escrow.generation override on a higher range must
        // partition the base coverage WITHOUT promoting AUTO to moduleConfigurations[0].
        val comp = makeComponent("COMP5E2")
        // Component-level escrow default = MANUAL (the real wscardsmodel base).
        val base = makeBase(comp, buildSystem = "ESCROW_PROVIDED_MANUALLY").apply {
            escrowGeneration = "MANUAL"
            createdAt = java.time.Instant.ofEpochMilli(1000)
        }
        // A GRADLE/AUTO release island on a higher bounded range (value-change edge).
        val autoIsland = makeScalarOverrideRow(comp, "[2.0,3.0)", "escrow.generation").apply {
            escrowGeneration = "AUTO"
            createdAt = java.time.Instant.ofEpochMilli(2000)
        }
        comp.configurations.addAll(listOf(base, autoIsland))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5E2")
        assertNotNull(module)
        // Base coverage = ALL; the single AUTO island edge splits it into three version-ordered views.
        assertEquals(
            listOf("(,2.0)", "[2.0,3.0)", "[3.0,)"),
            module!!.moduleConfigurations.map { it.versionRangeString },
        )
        val gen = { i: Int ->
            module.moduleConfigurations[i].escrow!!.generation.orElse(null)
        }
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.MANUAL,
            gen(0),
            "moduleConfigurations[0] is the lowest sub-range → inherits the MANUAL base, NOT the AUTO island",
        )
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.AUTO,
            gen(1),
            "the [2.0,3.0) island carries its explicit AUTO override",
        )
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.MANUAL,
            gen(2),
            "the tail above the island falls back to the MANUAL base",
        )
        // Primary invariant of the derive-from-base fix: the component-level representative is the
        // resolved BASE config (MANUAL), NOT the version-sorted moduleConfigurations[0] nor the AUTO island.
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.MANUAL,
            module.componentLevelConfiguration!!.escrow!!.generation.orElse(null),
            "component-level escrow.generation must come from the MANUAL base, not the higher AUTO island",
        )
    }

    @Test
    fun `(5e3 MIG-029) component-level representative comes from BASE, not the version-sorted first range`() {
        // Real tiling-shape component, confirmed against the live QA DB: BASE (,0),[0,) escrow=AUTO
        // (the Defaults value) + a low-version SCALAR_OVERRIDE escrow.generation=UNSUPPORTED on
        // (,03.51.29.15). V1's component-level escrow.generation = AUTO because V1's
        // moduleConfigurations[0] is the first DSL-declared (current/default) block; the redesign sorts
        // the enumeration partition by version ASC, so moduleConfigurations[0] became the lowest range
        // (the UNSUPPORTED historical override) — flipping the wire DTO to UNSUPPORTED for 12 components.
        //
        // FIX (ADR-018 redesign, derive-from-base): the component-level scalar representative is the
        // resolved BASE config, exposed as EscrowModule.componentLevelConfiguration and consumed by the
        // controllers — moduleConfigurations[0] is NO LONGER the implicit source of component-level
        // scalar truth. Enumeration stays version-sorted/atomic (asserted below), decoupling
        // "how ranges are enumerated" from "which config is the representative".
        val comp = makeComponent("COMP5E3")
        val base = makeBase(comp, buildSystem = "WHISKEY").apply {
            escrowGeneration = "AUTO"
            createdAt = java.time.Instant.ofEpochMilli(1000)
        }
        val lowUnsupported = makeScalarOverrideRow(comp, "(,03.51.29.15)", "escrow.generation").apply {
            escrowGeneration = "UNSUPPORTED"
            createdAt = java.time.Instant.ofEpochMilli(2000)
        }
        comp.configurations.addAll(listOf(base, lowUnsupported))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5E3")
        assertNotNull(module)
        // Component-level representative = BASE → AUTO (matches V1), regardless of enumeration order.
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.AUTO,
            module!!.componentLevelConfiguration!!.escrow!!.generation.orElse(null),
            "component-level escrow.generation must come from the AUTO base, not the low UNSUPPORTED override",
        )
        // Enumeration stays version-sorted/atomic: the low UNSUPPORTED range is still first in the list.
        assertEquals(
            listOf("(,03.51.29.15)", "[03.51.29.15,)"),
            module.moduleConfigurations.map { it.versionRangeString },
            "variants enumeration stays version-ordered (decoupled from the component-level representative)",
        )
        assertEquals(
            org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode.UNSUPPORTED,
            module.moduleConfigurations.first().escrow!!.generation.orElse(null),
            "the low range view still carries its UNSUPPORTED override (per-range values unchanged)",
        )
    }

    @Test
    @DisplayName(
        "MIG-029 (ADR-018): canonical [1.0,2.0)+[2.0,) enumeration — two declared ranges " +
            "(one open-upper) enumerate as two views; resolve gated to supported",
    )
    fun `(5f MIG-029) canonical two-range enumeration equivalence with open-upper tail`() {
        // The plan's canonical enumeration-equivalence check: a bounded-block-only component
        // [1.0,2.0)+[2.0,) migrates to ALL_VERSIONS base + two RANGE_PRESENCE rows + two scalar
        // overrides. Enumeration must emit exactly the two declared ranges (base view suppressed),
        // resolve must honor the open-upper tail, and versions below 1.0 are out of supported.
        val comp = makeComponent("COMP5F")
        val base = makeBase(comp, javaVersion = "17")
        val presenceLow = makeRangePresenceRow(comp, "[1.0,2.0)")
        val presenceHigh = makeRangePresenceRow(comp, "[2.0,)")
        val overLow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion").apply { javaVersion = "11" }
        val overHigh = makeScalarOverrideRow(comp, "[2.0,)", "build.javaVersion").apply { javaVersion = "21" }
        comp.configurations.addAll(listOf(base, presenceLow, presenceHigh, overLow, overHigh))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5F")
        assertNotNull(module)
        assertEquals(
            listOf("[1.0,2.0)", "[2.0,)"),
            module!!.moduleConfigurations.map { it.versionRangeString },
            "Enumeration emits exactly the two declared ranges (ALL_VERSIONS base view suppressed)",
        )
        assertEquals("11", resolver.getResolvedComponentDefinition("COMP5F", "1.5.0")!!.buildConfiguration?.javaVersion)
        assertEquals("21", resolver.getResolvedComponentDefinition("COMP5F", "2.5.0")!!.buildConfiguration?.javaVersion)
        // Open-upper tail keeps resolving above the highest declared bound.
        assertEquals("21", resolver.getResolvedComponentDefinition("COMP5F", "99.0.0")!!.buildConfiguration?.javaVersion)
        // Below the lowest declared bound → outside supported → null (V1 404).
        assertNull(resolver.getResolvedComponentDefinition("COMP5F", "0.5.0"))
    }

    // ========================================================================
    // (6) Doc-link resolution
    // ========================================================================

    @Test
    fun `(6) doc-link - per-major entry wins over null fallback`() {
        val comp = makeComponent("COMP6")
        // versionRange "[1.0,2.0)" → pickDocLink extracts leading digit sequence "1"
        val base = makeBase(comp, versionRange = "[1.0,2.0)", javaVersion = "8")
        comp.configurations.add(base)
        comp.docLinks.addAll(
            listOf(
                makeDocLink(comp, "DOCS", majorVersion = null, sortOrder = 0),
                // majorVersion "1" matches leadingMajor "1" extracted from "[1.0,2.0)"
                makeDocLink(comp, "DOCS", majorVersion = "1", sortOrder = 1),
                makeDocLink(comp, "DOCS", majorVersion = "2", sortOrder = 2),
            ),
        )
        stubComponent(comp)

        val module = resolver.getComponentById("COMP6")
        assertNotNull(module)
        val cfg = module!!.moduleConfigurations.first()
        assertNotNull(cfg.doc)
        assertEquals("1", cfg.doc!!.majorVersion())
    }

    @Test
    fun `(6b) doc-link - null fallback returned when no major matches`() {
        val comp = makeComponent("COMP6B")
        // versionRange "[5.0,6.0)" → leadingMajor "5"; no doc link has majorVersion "5"
        val base = makeBase(comp, versionRange = "[5.0,6.0)", javaVersion = "8")
        comp.configurations.add(base)
        comp.docLinks.addAll(
            listOf(
                makeDocLink(comp, "DOCS", majorVersion = null, sortOrder = 0),
                makeDocLink(comp, "DOCS", majorVersion = "1", sortOrder = 1),
            ),
        )
        stubComponent(comp)

        val module = resolver.getComponentById("COMP6B")
        val cfg = module!!.moduleConfigurations.first()
        assertNotNull(cfg.doc)
        // No "5" entry → null-fallback wins; its majorVersion field is null
        assertNull(cfg.doc!!.majorVersion())
    }

    @Test
    fun `(6c) doc-link - no doc links yields null doc on config`() {
        val comp = makeComponent("COMP6C")
        val base = makeBase(comp, versionRange = "[1.0,2.0)", javaVersion = "8")
        comp.configurations.add(base)
        // No docLinks added
        stubComponent(comp)

        val module = resolver.getComponentById("COMP6C")
        val cfg = module!!.moduleConfigurations.first()
        assertNull(cfg.doc)
    }

    // ========================================================================
    // (7) Distribution GAV concatenation
    // ========================================================================

    @Test
    fun `(7) distribution GAV - two maven then one file-url artifact in sort-order`() {
        val comp = makeComponent("COMP7")
        comp.distributionExplicit = true
        val base = makeBase(comp)
        // Maven artifacts at sortOrder 0 and 1
        base.mavenArtifacts.add(makeMavenArtifact(base, "com.example", "alpha", sortOrder = 0))
        base.mavenArtifacts.add(makeMavenArtifact(base, "com.example", "beta", sortOrder = 1))
        // File-URL artifact at sortOrder 2 (appended after maven)
        base.fileUrlArtifacts.add(makeFileUrlArtifact(base, "https://files.example.com/gamma.zip", sortOrder = 2))
        comp.configurations.add(base)
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP7", "1.0.0")
        assertNotNull(cfg)
        assertNotNull(cfg!!.distribution)
        val gav = cfg.distribution!!.GAV()
        assertNotNull(gav)
        assertEquals(
            "com.example:alpha,com.example:beta,https://files.example.com/gamma.zip",
            gav,
        )
    }

    // ========================================================================
    // (8) Build required-tools marker override
    // ========================================================================

    @Test
    fun `(8) build required-tools marker - Whiskey tool surfaces in BuildParameters for 1·5·0`() {
        val comp = makeComponent("COMP8")
        // Base needs at least one non-null build param so toBuildParameters returns non-null
        val base = makeBase(comp, javaVersion = "11")
        base.vcsEntries.add(makeVcsEntry(base, "ssh://vcs/comp8"))

        // Marker override for build.requiredTools at [1.0,2.0)
        val toolsMarker = makeMarkerRow(comp, "[1.0,2.0)", "build.requiredTools")
        toolsMarker.requiredToolJunctions.add(makeToolJunction("Whiskey"))

        comp.configurations.addAll(listOf(base, toolsMarker))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP8", "1.5.0")
        assertNotNull(cfg)
        val buildParams = cfg!!.buildConfiguration
        assertNotNull(buildParams)
        val tools = buildParams!!.tools
        assertNotNull(tools)
        assertEquals(1, tools!!.size)
        assertEquals("Whiskey", tools.first().name)
    }

    @Test
    fun `(8b) build required-tools marker - version outside range gets empty tool list`() {
        val comp = makeComponent("COMP8B")
        val base = makeBase(comp, javaVersion = "11")
        val toolsMarker = makeMarkerRow(comp, "[1.0,2.0)", "build.requiredTools")
        toolsMarker.requiredToolJunctions.add(makeToolJunction("Whiskey"))
        comp.configurations.addAll(listOf(base, toolsMarker))
        stubComponent(comp)

        // 2.0.0 is the exclusive upper bound — tool marker does NOT apply
        val cfg = resolver.getResolvedComponentDefinition("COMP8B", "2.0.0")
        assertNotNull(cfg)
        val buildParams = cfg!!.buildConfiguration
        assertNotNull(buildParams)
        // No tool marker matched → tools list is empty
        assertEquals(0, buildParams!!.tools.size)
    }

    // ========================================================================
    // (9) MIG-042: configured-range gate — version outside EVERY configured
    // range → null (mirrors V1 EscrowConfigurationLoader.resolveComponentConfiguration
    // returning no config → HTTP 404). Tests 9a–9e salvaged from the
    // fix/mig-042-version-range-gate branch (e3d252c8 + 923d6c71); 9f is the
    // gap case those iterations never covered — the live cluster-A shape.
    // ========================================================================

    @Test
    @DisplayName("MIG-042: base with bounded range - version inside range resolves non-null")
    fun `(9a MIG-042) base with bounded range - version inside range resolves`() {
        val comp = makeComponent("COMP9A")
        val base = makeBase(comp, versionRange = "[1.0,2.0)", javaVersion = "11")
        comp.configurations.add(base)
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP9A", "1.5.0")
        assertNotNull(cfg)
        assertEquals("11", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    @DisplayName("MIG-042: base with bounded range - version outside range returns null (mirrors V1 404)")
    fun `(9b MIG-042) base with bounded range - version outside range returns null`() {
        val comp = makeComponent("COMP9B")
        val base = makeBase(comp, versionRange = "[1.0,2.0)", javaVersion = "11")
        comp.configurations.add(base)
        stubComponent(comp)

        // 3.0.0 is outside [1.0,2.0) → V1 returns 404 → v3 must return null
        assertNull(resolver.getResolvedComponentDefinition("COMP9B", "3.0.0"))
    }

    @Test
    @DisplayName("MIG-042: base with ALL_VERSIONS range - any version still resolves (no regression)")
    fun `(9c MIG-042) base with ALL_VERSIONS range - any version still resolves`() {
        val comp = makeComponent("COMP9C")
        val base = makeBase(comp, javaVersion = "8")
        comp.configurations.add(base)
        stubComponent(comp)

        assertNotNull(resolver.getResolvedComponentDefinition("COMP9C", "0.0.1"))
        assertNotNull(resolver.getResolvedComponentDefinition("COMP9C", "999.0.0"))
    }

    @Test
    @DisplayName("MIG-042: non-synthetic single-range base - version outside range returns null")
    fun `(9d MIG-042) non-synthetic single-range base - out-of-range returns null`() {
        // A component with exactly ONE explicit range block (isSyntheticBase = false):
        // the BASE row's range IS the component's whole configured range. V1 has no
        // config for a version outside it → 404 → null here.
        val comp = makeComponent("COMP9D")
        val base = makeBase(comp, versionRange = "[1.0,2.0)", isSyntheticBase = false, javaVersion = "11")
        comp.configurations.add(base)
        stubComponent(comp)

        assertNull(resolver.getResolvedComponentDefinition("COMP9D", "0.5.0"))
        assertNull(resolver.getResolvedComponentDefinition("COMP9D", "3.0.0"))
        val cfg = resolver.getResolvedComponentDefinition("COMP9D", "1.5.0")
        assertNotNull(cfg)
        assertEquals("11", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    @DisplayName("MIG-042: synthetic-base multi-range - version in second range resolves (V1 parity)")
    fun `(9e MIG-042) synthetic-base multi-range - version in second range resolves`() {
        // Two adjacent DSL range blocks "(,1.0)" + "[1.0,)": the synthetic BASE row
        // holds only the FIRST block's range. A version covered by the SECOND block
        // must still resolve — gating on the base block alone broke whole component
        // families on compat run 3823 (the original MIG-042 over-reach).
        val comp = makeComponent("COMP9E")
        val base = makeBase(comp, versionRange = "(,1.0)", isSyntheticBase = true, javaVersion = "8")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val cfgOverride = resolver.getResolvedComponentDefinition("COMP9E", "1.5.0")
        assertNotNull(cfgOverride)
        assertEquals("11", cfgOverride!!.buildConfiguration?.javaVersion)

        val cfgBase = resolver.getResolvedComponentDefinition("COMP9E", "0.5.0")
        assertNotNull(cfgBase)
        assertEquals("8", cfgBase!!.buildConfiguration?.javaVersion)
    }

    @Test
    @DisplayName("MIG-042: synthetic-base multi-range with a GAP - version in the gap returns null (cluster A)")
    fun `(9f MIG-042) synthetic-base multi-range with gap - version in gap returns null`() {
        // The live cluster-A shape (V1 oracle, curl 2026-06-07): a component declares
        // range blocks [10,11), [11,12.1) and [12.2,) — versions 12.1.x fall in the
        // GAP between the last two blocks. V1 resolves NO configuration there and the
        // endpoint answers 404 {"errorMessage":"Component id <comp>:12.1.155 is not
        // found"}; v3 must return null instead of over-resolving from the base row.
        // The component's effective range is the UNION of all its blocks.
        val comp = makeComponent("COMP9F")
        val base = makeBase(comp, versionRange = "[10,11)", isSyntheticBase = true, javaVersion = "7")
        val second = makeScalarOverrideRow(comp, "[11,12.1)", "build.javaVersion")
        second.javaVersion = "8"
        val third = makeScalarOverrideRow(comp, "[12.2,)", "build.javaVersion")
        third.javaVersion = "17"
        comp.configurations.addAll(listOf(base, second, third))
        stubComponent(comp)

        // 12.1.155 / 12.1.156 are in the gap [12.1,12.2) → V1 404 → null
        assertNull(resolver.getResolvedComponentDefinition("COMP9F", "12.1.155"))
        assertNull(resolver.getResolvedComponentDefinition("COMP9F", "12.1.156"))
        // versions covered by each block still resolve
        assertEquals("7", resolver.getResolvedComponentDefinition("COMP9F", "10.5")!!.buildConfiguration?.javaVersion)
        assertEquals("8", resolver.getResolvedComponentDefinition("COMP9F", "11.5")!!.buildConfiguration?.javaVersion)
        assertEquals("17", resolver.getResolvedComponentDefinition("COMP9F", "12.2.5")!!.buildConfiguration?.javaVersion)
        // below every block → null as well
        assertNull(resolver.getResolvedComponentDefinition("COMP9F", "9.0"))
    }

    @Test
    @DisplayName("MIG-042: NON-synthetic multi-range - version covered by an override block resolves (gate uses the union)")
    fun `(9g MIG-042) non-synthetic multi-range - version in override block resolves`() {
        // Live-victim shape from the first union-gate iteration (59 NEW on the
        // full gate): a component WITH top-level scalars (isSyntheticBase=false)
        // AND multiple DSL range blocks. The BASE row carries only the first
        // block's range; later blocks live on override rows. Gating non-synthetic
        // bases on the BASE range alone 404'd every version covered by a later
        // block (V1 answers 200 there). The effective range is ALWAYS the union
        // of base + override ranges, regardless of the synthetic flag.
        val comp = makeComponent("COMP9G")
        val base = makeBase(comp, versionRange = "[1.0,1.1)", isSyntheticBase = false, javaVersion = "8")
        val later = makeScalarOverrideRow(comp, "[1.1,)", "build.javaVersion")
        later.javaVersion = "17"
        comp.configurations.addAll(listOf(base, later))
        stubComponent(comp)

        // 1.1.759 is outside the BASE block but inside the later block → resolves (V1=200)
        val cfg = resolver.getResolvedComponentDefinition("COMP9G", "1.1.759")
        assertNotNull(cfg)
        assertEquals("17", cfg!!.buildConfiguration?.javaVersion)
        // base block still resolves
        assertEquals("8", resolver.getResolvedComponentDefinition("COMP9G", "1.0.5")!!.buildConfiguration?.javaVersion)
        // below every block → null (V1 404)
        assertNull(resolver.getResolvedComponentDefinition("COMP9G", "0.5"))
    }

    @Test
    @DisplayName("MIG-042: version covered only by an EMPTY DSL block (RANGE_PRESENCE row) resolves — union includes presence rows")
    fun `(9h MIG-042) version covered by a RANGE_PRESENCE row resolves`() {
        // Second live-victim shape from the full gate (59 NEW persisted through
        // iteration 2): components whose covering DSL blocks are EMPTY
        // ("[1.1,2.0)" {}) — imported as RANGE_PRESENCE rows, not
        // SCALAR_OVERRIDE/MARKER. The union gate must count EVERY non-BASE
        // row's range; a presence row contributes no overrides but proves the
        // version is configured (V1 answers 200 with base-inherited data).
        val comp = makeComponent("COMP9H")
        val base = makeBase(comp, versionRange = "[1.0,1.1)", isSyntheticBase = false, javaVersion = "8")
        val presence =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[1.1,2.0)",
                rowType = "RANGE_PRESENCE",
            )
        comp.configurations.addAll(listOf(base, presence))
        stubComponent(comp)

        // 1.1.759 is covered only by the presence row → must resolve with base config
        val cfg = resolver.getResolvedComponentDefinition("COMP9H", "1.1.759")
        assertNotNull(cfg)
        assertEquals("8", cfg!!.buildConfiguration?.javaVersion)
        // 2.5 is in the gap above every block → null (V1 404)
        assertNull(resolver.getResolvedComponentDefinition("COMP9H", "2.5"))
    }

    // ========================================================================
    // Companion
    // ========================================================================

    companion object {
        /** Must match EscrowConfigurationLoader.ALL_VERSIONS */
        private const val ALL_VERSIONS = "(,0),[0,)"
    }
}
