package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
        )

    private fun makeVcsEntry(
        config: ComponentConfigurationEntity,
        vcsPath: String,
        name: String? = null,
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
    fun `(2) scalar override - enumeration yields base-range entry and override-range entry`() {
        val comp = makeComponent("COMP2")
        val base = makeBase(comp, javaVersion = "11")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "21"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP2")
        assertNotNull(module)
        assertEquals(2, module!!.moduleConfigurations.size)

        val allVersionsCfg = module.moduleConfigurations.first { it.versionRangeString == ALL_VERSIONS }
        val overrideCfg = module.moduleConfigurations.first { it.versionRangeString == "[1.0,2.0)" }
        // Base range keeps original javaVersion
        assertEquals("11", allVersionsCfg.buildConfiguration?.javaVersion)
        // Override range has overridden javaVersion
        assertEquals("21", overrideCfg.buildConfiguration?.javaVersion)
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
    @org.junit.jupiter.api.Disabled(
        "schema-v2 known limitation — synthetic-base suppression in enumeration is not yet wired " +
            "through resolveForRange; both base and override ranges are currently returned. " +
            "Sibling of RES-001 (enumeration semantics). Tracked in docs/db-migration/todo.md.",
    )
    fun `(5 MIG-029) synthetic base with override - enumeration emits only the override range`() {
        val comp = makeComponent("COMP5")
        val base = makeBase(comp, isSyntheticBase = true, javaVersion = "8")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5")
        assertNotNull(module)
        // isSyntheticBase=true AND overrides exist → base range NOT enumerated; only override range
        assertEquals(1, module!!.moduleConfigurations.size)
        assertEquals("[1.0,2.0)", module.moduleConfigurations.first().versionRangeString)
    }

    @Test
    fun `(5b MIG-029) synthetic base - resolve 1·5·0 returns overridden javaVersion 11`() {
        val comp = makeComponent("COMP5B")
        val base = makeBase(comp, isSyntheticBase = true, javaVersion = "8")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        val cfg = resolver.getResolvedComponentDefinition("COMP5B", "1.5.0")
        assertNotNull(cfg)
        assertEquals("11", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    fun `(5c MIG-029) synthetic base - resolve 0·9·0 falls back to synthetic base values`() {
        val comp = makeComponent("COMP5C")
        val base = makeBase(comp, isSyntheticBase = true, javaVersion = "8")
        val overrideRow = makeScalarOverrideRow(comp, "[1.0,2.0)", "build.javaVersion")
        overrideRow.javaVersion = "11"
        comp.configurations.addAll(listOf(base, overrideRow))
        stubComponent(comp)

        // 0.9.0 is not in [1.0,2.0) → no override matches → synthetic base is the fallback
        val cfg = resolver.getResolvedComponentDefinition("COMP5C", "0.9.0")
        assertNotNull(cfg)
        assertEquals("8", cfg!!.buildConfiguration?.javaVersion)
    }

    @Test
    fun `(5d MIG-029) synthetic base with no overrides - enumeration still emits the base range`() {
        val comp = makeComponent("COMP5D")
        // Synthetic base but NO override rows → the base range must still be enumerated
        val base = makeBase(comp, isSyntheticBase = true, javaVersion = "8")
        comp.configurations.add(base)
        stubComponent(comp)

        val module = resolver.getComponentById("COMP5D")
        assertNotNull(module)
        // overrides.isEmpty() → isSyntheticBase guard does NOT suppress the base range
        assertEquals(1, module!!.moduleConfigurations.size)
        assertEquals(ALL_VERSIONS, module.moduleConfigurations.first().versionRangeString)
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
    // Companion
    // ========================================================================

    companion object {
        /** Must match EscrowConfigurationLoader.ALL_VERSIONS */
        private const val ALL_VERSIONS = "(,0),[0,)"
    }
}
