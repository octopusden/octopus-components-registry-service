package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MIG-039: `find-by-artifacts` must mirror V1's `EscrowModuleConfigMatcher` —
 * a group/artifact pattern match is necessary but NOT sufficient; the artifact
 * version must also fall within one of the component's configuration version
 * ranges.
 *
 * Bug (live-repro 2026-06-02): for an artifact `<group>:<artifact>:11.1.157` the DB
 * resolver resolved an **archived** component instead of the correct active one.
 * Both carry a `component_artifact_ids` row whose pattern matches `<artifact>`, but
 * the archived one's ranges (`[1.0.x,…)`) exclude `11.1.157`. The DB matcher ignored
 * version ranges and tie-broke by `artifactPattern.length`, so the archived
 * component's long `|`-union pattern won. V1 gates by `versionRange.containsVersion`,
 * so it uniquely picks the in-range (active) component.
 *
 * Mock-based (no Spring / DB), mirroring DatabaseComponentRegistryResolverMavenArtifactsRangeTest.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class DatabaseComponentRegistryResolverFindByArtifactTest {
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

    private fun makeComponent(key: String): ComponentEntity = ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun addConfig(
        component: ComponentEntity,
        versionRange: String,
    ) {
        component.configurations.add(
            ComponentConfigurationEntity(
                component = component,
                versionRange = versionRange,
                overriddenAttribute = null,
                rowType = "BASE",
                deprecated = false,
            ),
        )
    }

    private fun addArtifactId(
        component: ComponentEntity,
        groupPattern: String,
        artifactPattern: String,
    ) {
        component.addOwnershipMapping(groupPattern, artifactPattern)
    }

    /** A per-range `GROUP_ARTIFACT_PATTERN` marker carrying an overriding maven coordinate. */
    private fun addMarkerWithMavenArtifact(
        component: ComponentEntity,
        versionRange: String,
        groupPattern: String,
        artifactPattern: String,
    ) {
        // Per-range ownership override (the new model — a mapping with the override range, which
        // replaces the base mapping for that range; ownership no longer rides a marker row).
        component.addOwnershipMapping(groupPattern, artifactPattern, versionRange)
    }

    @Test
    @DisplayName("MIG-039: resolves the in-range component, not an archived one whose ranges exclude the version")
    fun `MIG-039 gates artifact resolution by configuration version range`() {
        // Active component: matches 'core' via a |-union pattern (specificity == union, same
        // class as the archived one below, so the tie-break falls through to pattern LENGTH);
        // range contains 11.1.157.
        val active = makeComponent("active-comp")
        addArtifactId(active, "com.example.system", "core|api")
        addConfig(active, "[11.0.0,12.0.0)")

        // Archived component: ALSO matches 'core' via a LONGER |-union pattern (equal specificity,
        // so the old length tie-break preferred it), but its range excludes 11.1.157.
        val archived = makeComponent("archived-comp")
        addArtifactId(archived, "com.example.system", "core|extra|legacy|more|verylongunion")
        addConfig(archived, "[1.0.0,2.0.0)")

        `when`(componentRepository.findAll()).thenReturn(mutableListOf(active, archived))

        val artifact = ArtifactDependency("com.example.system", "core", "11.1.157")
        val resolved = resolver.findComponentsByArtifact(setOf(artifact))[artifact]

        assertNotNull(resolved, "artifact must resolve to the in-range component")
        assertEquals(
            "active-comp",
            resolved!!.id,
            "must pick the version-range-matching component, not the archived one with a longer pattern",
        )
    }

    @Test
    @DisplayName("MIG-039: returns null when no component config range contains the artifact version")
    fun `MIG-039 returns null when version is outside all matching components ranges`() {
        val comp = makeComponent("active-comp")
        addArtifactId(comp, "com.example.system", "core")
        addConfig(comp, "[11.0.0,12.0.0)")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))

        // pattern matches 'core' but 99.0.0 is outside [11.0.0,12.0.0)
        val artifact = ArtifactDependency("com.example.system", "core", "99.0.0")
        assertNull(resolver.findComponentsByArtifact(setOf(artifact))[artifact])
    }

    @Test
    @DisplayName("MIG-039: returns null for an artifact matching no component pattern")
    fun `MIG-039 returns null for an unknown artifact`() {
        val comp = makeComponent("active-comp")
        addArtifactId(comp, "com.example.system", "core")
        addConfig(comp, "[11.0.0,12.0.0)")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))

        val artifact = ArtifactDependency("com.other.group", "totally-unrelated", "11.1.0")
        assertNull(resolver.findComponentsByArtifact(setOf(artifact))[artifact])
    }

    @Test
    @DisplayName("MIG-039: per-range artifactId override — the new artifact resolves in its own range")
    fun `MIG-039 resolves a per-range artifactId override in the overriding range`() {
        // base [1.0.0,2.0.0) publishes 'old' (component-level); a GROUP_ARTIFACT_PATTERN marker
        // overrides [2.0.0,) to publish 'new'. V1 matches artifact pattern AND range on the SAME
        // config, so 'new:2.5.0' must resolve via the [2.0.0,) range (it isn't in the
        // component-level artifactIds at all — the pre-fix component-level match would miss it).
        val comp = makeComponent("ovr")
        addArtifactId(comp, "com.example.x", "old")
        addConfig(comp, "[1.0.0,2.0.0)")
        addMarkerWithMavenArtifact(comp, "[2.0.0,)", "com.example.x", "new")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))

        val artifact = ArtifactDependency("com.example.x", "new", "2.5.0")
        val resolved = resolver.findComponentsByArtifact(setOf(artifact))[artifact]
        assertNotNull(resolved, "the per-range 'new' artifact must resolve in [2.0.0,)")
        assertEquals("ovr", resolved!!.id)
    }

    @Test
    @DisplayName("MIG-039: per-range artifactId override — a superseded artifact does NOT resolve for a version in the override range")
    fun `MIG-039 does not resolve a superseded artifactId for a version in the overriding range`() {
        val comp = makeComponent("ovr")
        addArtifactId(comp, "com.example.x", "old")
        addConfig(comp, "[1.0.0,2.0.0)")
        addMarkerWithMavenArtifact(comp, "[2.0.0,)", "com.example.x", "new")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(comp))

        // 'old' was published only in [1.0.0,2.0.0); at 2.5.0 the effective artifact is 'new', so
        // 'old:2.5.0' must NOT resolve (the pre-fix gate accepted it because some range covered 2.5.0
        // and the component-level 'old' matched).
        val artifact = ArtifactDependency("com.example.x", "old", "2.5.0")
        assertNull(resolver.findComponentsByArtifact(setOf(artifact))[artifact])
    }

    @Test
    @DisplayName("MIG-023/039: a specific (exact) artifactId mapping beats a generic one regardless of findAll() order")
    fun `MIG-039 prefers the most specific in-range mapping over a generic one listed first`() {
        // MIG-023: when two components match the same artifact in range, the more specific
        // (exact) mapping must win — even if findAll() returns the generic one first.
        val generic = makeComponent("generic-comp")
        addArtifactId(generic, "com.example.x", "core|other") // |-union → less specific
        addConfig(generic, "[1.0.0,2.0.0)")
        val specific = makeComponent("specific-comp")
        addArtifactId(specific, "com.example.x", "core") // exact → more specific
        addConfig(specific, "[1.0.0,2.0.0)")
        // generic is returned FIRST — a first-in-range-wins matcher would (wrongly) pick it.
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(generic, specific))

        val artifact = ArtifactDependency("com.example.x", "core", "1.5.0")
        val resolved = resolver.findComponentsByArtifact(setOf(artifact))[artifact]
        assertNotNull(resolved)
        assertEquals(
            "specific-comp",
            resolved!!.id,
            "the exact mapping must win over the generic |-union one regardless of findAll() order",
        )
    }

    @Test
    @DisplayName("MIG-023/039: the inherited default catch-all regex must lose to a concrete multi-artifact mapping")
    fun `MIG-039 ranks the default catch-all regex below a concrete union mapping`() {
        // A component without an explicit artifactId inherits the default ANY_ARTIFACT regex,
        // stored verbatim (e.g. [\w-\.]+). MIG-023: it must NOT beat a component that lists the
        // concrete artifact in a |-union, even though the regex isn't the literal "*".
        val generic = makeComponent("generic-comp")
        addArtifactId(generic, "com.example.x", "[\\w-\\.]+") // inherited default catch-all regex
        addConfig(generic, "[1.0.0,2.0.0)")
        val concrete = makeComponent("concrete-comp")
        addArtifactId(concrete, "com.example.x", "core|api") // concrete multi-artifact union
        addConfig(concrete, "[1.0.0,2.0.0)")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(generic, concrete))

        val artifact = ArtifactDependency("com.example.x", "core", "1.5.0")
        val resolved = resolver.findComponentsByArtifact(setOf(artifact))[artifact]
        assertNotNull(resolved)
        assertEquals(
            "concrete-comp",
            resolved!!.id,
            "the default catch-all regex must rank below a concrete union mapping",
        )
    }

    @Test
    @DisplayName("MIG-023/039: the dot-less default catch-all regex form also loses to a concrete mapping")
    fun `MIG-039 ranks the dotless default catch-all regex below a concrete union mapping`() {
        // Some fixtures store the default as [\w-]+ (no dot). It must also be treated as catch-all,
        // not specific — the catch-all probe must therefore contain no dot/dash.
        val generic = makeComponent("generic-comp")
        addArtifactId(generic, "com.example.x", "[\\w-]+") // dot-less default catch-all regex
        addConfig(generic, "[1.0.0,2.0.0)")
        val concrete = makeComponent("concrete-comp")
        addArtifactId(concrete, "com.example.x", "core|api")
        addConfig(concrete, "[1.0.0,2.0.0)")
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(generic, concrete))

        val artifact = ArtifactDependency("com.example.x", "core", "1.5.0")
        val resolved = resolver.findComponentsByArtifact(setOf(artifact))[artifact]
        assertNotNull(resolved)
        assertEquals(
            "concrete-comp",
            resolved!!.id,
            "the dot-less [\\w-]+ catch-all must rank below a concrete union mapping",
        )
    }
}
