package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentRequiredToolEntity
import org.octopusden.octopus.components.registry.server.entity.ToolEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Range-only parity guard (see #362 for the related migration-time validation).
 *
 * Pins V1 ↔ v3 behavioural parity for the "component-own default + a single
 * `[1.0.700,)` block" DSL shape, where the lowest declared range starts above
 * the component's earliest released versions. The V1 loader builds
 * `moduleConfigurations` ONLY from the range block, so a version below 1.0.700
 * (here `1.0.1`) matches no module and resolves to `null` (HTTP 404) — the
 * component-own default `javaVersion = "17"` is never applied to any version.
 * The v3 import mirrors this as a single **non-synthetic** BASE row with
 * `versionRange = [1.0.700,)` (`isSyntheticBase = false`), so the DB-mode
 * resolver's range gate must produce the same not-found across every read API.
 *
 * This is NOT a regression — both stacks agree. The guard exists so a future
 * change to either the V1 loader's range expansion or the v3 range gate cannot
 * silently diverge for this shape.
 *
 * V1 side: the real `EscrowConfigurationLoader` over the self-contained fixture
 * `range-only-guard/GuardComponent.groovy` (loader wiring replicated from
 * `component-resolver-core`'s `TestConfigUtils`, which is test-only and not
 * visible cross-module). v3 side: a hand-built entity graph driven through
 * `DatabaseComponentRegistryResolver` with a mocked repository (the established
 * `DatabaseComponentRegistryResolverTest` pattern). Plain mocked-repo unit test
 * — no Spring/DB context — so it runs under `:test` / `:build`, not `:dbTest`.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class RangeOnlyParityGuardTest {
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

    // ====================================================================
    // V1 side — real loader over the fixture
    // ====================================================================

    private fun loadV1Configuration(): EscrowConfiguration {
        val productTypes = ProductTypes.values().associateWith { it.name }
        val loader = EscrowConfigurationLoader(
            ConfigLoader(
                ComponentRegistryInfo.fromClassPath("range-only-guard/GuardComponent.groovy"),
                versionNames,
                productTypes,
            ),
            listOf("org.octopusden.octopus", "io.bcomponent"),
            listOf("NONE", "CLASSIC", "ALFA"),
            versionNames,
            // The loader validates that the copyright path is an existing directory, but only
            // reads it when a component declares `copyright`. The fixture declares none, so an
            // empty temp dir satisfies the check without shipping a repo artifact.
            java.nio.file.Files
                .createTempDirectory("range-only-guard-copyrights"),
        )
        return loader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap<String, String>())
    }

    private fun resolveV1(
        config: EscrowConfiguration,
        version: String,
    ): EscrowModuleConfig? =
        EscrowConfigurationLoader.resolveComponentConfiguration(
            config,
            ComponentVersion.create(COMPONENT, version),
        )

    // ====================================================================
    // v3 side — entity graph mirroring the imported shape
    // ====================================================================

    /**
     * Single non-synthetic BASE row at `[1.0.700,)` carrying `javaVersion = 21`
     * (the range override) and the inherited `GuardTool` required-tool junction
     * (the range block overrode only `javaVersion`, not tools). This is exactly
     * what `ImportServiceImpl` produces for the fixture: `firstOrNull { ALL_VERSIONS }
     * ?: configs.first()` selects the sole `[1.0.700,)` config as the base.
     */
    private fun buildV3Component(): ComponentEntity {
        val comp = ComponentEntity(id = UUID.randomUUID(), componentKey = COMPONENT)
        val base = ComponentConfigurationEntity(
            component = comp,
            versionRange = IN_RANGE_LOWER_BOUND,
            overriddenAttribute = null,
            rowType = "BASE",
            isSyntheticBase = false,
            buildSystem = "MAVEN",
            javaVersion = "21",
            jiraProjectKey = "GUARD",
            deprecated = false,
        )
        base.jiraMinorVersionFormat = "\$major"
        base.jiraReleaseVersionFormat = "\$major.\$minor.\$service"
        base.vcsEntries.add(
            VcsSettingsEntryEntity(
                componentConfiguration = base,
                vcsPath = "ssh://git@example/guard",
                name = "main",
                branch = "master",
                sortOrder = 0,
            ),
        )
        base.requiredToolJunctions.add(
            ComponentRequiredToolEntity(toolName = "GuardTool", tool = ToolEntity(name = "GuardTool")),
        )
        comp.configurations.add(base)
        return comp
    }

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    // ====================================================================
    // Tests
    // ====================================================================

    @Test
    @DisplayName("out-of-range 1.0.1 is not-found in BOTH V1 and v3 (component-own default never applies)")
    fun `out of range version is not-found in V1 and v3 alike`() {
        // --- V1 ---
        val v1Config = loadV1Configuration()
        val v1Resolved = resolveV1(v1Config, OUT_OF_RANGE)
        assertNull(v1Resolved, "V1 must return null for $OUT_OF_RANGE (no module covers it)")

        // sanity: V1 does resolve a version that IS in range, so the null above is the
        // range gate, not a broken fixture.
        assertNotNull(resolveV1(v1Config, IN_RANGE), "V1 must resolve in-range $IN_RANGE")

        // --- v3 ---
        val comp = buildV3Component()
        stubComponent(comp)

        // component detail (GET /v2/components/{c}?version=) → toResolvedEscrowModuleConfig
        assertNull(
            resolver.getResolvedComponentDefinition(COMPONENT, OUT_OF_RANGE),
            "v3 getResolvedComponentDefinition must be null for $OUT_OF_RANGE (range gate)",
        )
        // detailed-version / jira-component → getJiraComponentVersion
        assertThrows<NotFoundException> {
            resolver.getJiraComponentVersion(COMPONENT, OUT_OF_RANGE)
        }
        // vcs-settings → getVCSSettings
        assertThrows<NotFoundException> {
            resolver.getVCSSettings(COMPONENT, OUT_OF_RANGE)
        }
        // batch detailed-versions → getJiraComponentVersions silently drops the unresolvable version
        val batch = resolver.getJiraComponentVersions(COMPONENT, listOf(OUT_OF_RANGE, IN_RANGE))
        assertEquals(setOf(IN_RANGE), batch.keys, "Only the in-range version may appear in the batch map")
    }

    @Test
    @DisplayName("in-range 1.0.700 resolves identically in V1 and v3 (java 21 + inherited GuardTool)")
    fun `in range version resolves with same javaVersion and tool in V1 and v3`() {
        // --- V1 ---
        val v1Config = loadV1Configuration()
        val v1Resolved = resolveV1(v1Config, IN_RANGE)
        assertNotNull(v1Resolved, "V1 must resolve in-range $IN_RANGE")
        assertEquals("21", v1Resolved!!.buildConfiguration?.javaVersion, "V1 in-range javaVersion is the override 21")
        assertTrue(
            v1Resolved.buildConfiguration?.tools?.any { it.name == "GuardTool" } == true,
            "V1 in-range config inherits the component-own default required tool GuardTool",
        )

        // --- v3 ---
        val comp = buildV3Component()
        stubComponent(comp)
        val v3Resolved = resolver.getResolvedComponentDefinition(COMPONENT, IN_RANGE)
        assertNotNull(v3Resolved, "v3 must resolve in-range $IN_RANGE")
        assertEquals("21", v3Resolved!!.buildConfiguration?.javaVersion, "v3 in-range javaVersion is the override 21")
        assertTrue(
            v3Resolved.buildConfiguration?.tools?.any { it.name == "GuardTool" } == true,
            "v3 in-range config carries the inherited GuardTool required tool",
        )

        // the jira/vcs read paths resolve in-range on the v3 side
        assertNotNull(resolver.getJiraComponentVersion(COMPONENT, IN_RANGE))
        assertNotNull(resolver.getVCSSettings(COMPONENT, IN_RANGE))
    }

    companion object {
        private const val COMPONENT = "guardComponent"
        private const val IN_RANGE_LOWER_BOUND = "[1.0.700,)"
        private const val IN_RANGE = "1.0.700"
        private const val OUT_OF_RANGE = "1.0.1"
    }
}
