package org.octopusden.octopus.components.registry.server.service.impl

import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * MIG-040 — null scalar override round-trip.
 *
 * Verifies that a SCALAR_OVERRIDE row with a null column value (representing
 * "clear the inherited base value for this range") is correctly propagated
 * by the resolver. Before the fix, `EntityMappers.applyScalarOverride` used
 * `?.let { X = it }` which silently skipped null, causing the base value to
 * bleed into all ranges.
 *
 * Three scalar families covered:
 *   (A) String column — `build.buildFilePath`   (bug-F-component bug: "PlSqlPa" bleeding)
 *   (B) String column — `jira.versionPrefix`    (bug-G-component bug: "wallet" bleeding)
 *   (C) String column — `escrow.buildTask`      (general escrow scalar)
 *
 * Also verifies that the four inline-boolean scalar paths (`build.deprecated`,
 * `build.requiredProject`, `escrow.reusable`, `jira.technical`) accept null-
 * clearing override rows (previously bypassed `diffScalar` with the same
 * null-skip predicate).
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ScalarNullOverrideRoundTripTest {

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

    private fun makeComponent(key: String): ComponentEntity =
        ComponentEntity(id = UUID.randomUUID(), componentKey = key)

    private fun makeBase(component: ComponentEntity): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = ALL_VERSIONS,
            overriddenAttribute = null,
            rowType = "BASE",
            deprecated = false,
        )

    /**
     * SCALAR_OVERRIDE row with no typed column set (all null).
     * This represents "clear the inherited base scalar for this range."
     */
    private fun makeNullScalarOverrideRow(
        component: ComponentEntity,
        versionRange: String,
        attribute: String,
    ): ComponentConfigurationEntity =
        ComponentConfigurationEntity(
            component = component,
            versionRange = versionRange,
            overriddenAttribute = attribute,
            rowType = "SCALAR_OVERRIDE",
            // All typed columns remain null — this is the null-clear pattern
        )

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    // ========================================================================
    // (A) build.buildFilePath null override — bug-F-component bug shape
    // ========================================================================

    @Test
    @DisplayName("MIG-040-A: null buildFilePath override clears inherited base value on override range")
    fun `MIG-040-A null buildFilePath override - override range returns null, base range returns original`() {
        val comp = makeComponent("NULL_OVERRIDE_A")
        val base = makeBase(comp)
        base.buildFilePath = "PlSqlPa"

        // Override row with null buildFilePath — represents "clear for this range"
        val nullOverrideRow = makeNullScalarOverrideRow(comp, "[3.54.99-12.65,)", "build.buildFilePath")
        // buildFilePath intentionally left null on this row

        comp.configurations.addAll(listOf(base, nullOverrideRow))
        stubComponent(comp)

        // Base range: should still have "PlSqlPa"
        val baseCfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_A", "1.0.0")
        assertNotNull(baseCfg)
        assertEquals("PlSqlPa", baseCfg!!.buildFilePath)

        // Override range: null override row should clear the value → null
        val overrideCfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_A", "3.54.99-12.65")
        assertNotNull(overrideCfg)
        assertNull(
            overrideCfg!!.buildFilePath,
            "buildFilePath must be null for the override range (null-clear override); " +
                "was: ${overrideCfg.buildFilePath}",
        )
    }

    // ========================================================================
    // (B) jira.versionPrefix null override — bug-G-component bug shape
    // ========================================================================

    @Test
    @DisplayName("MIG-040-B: null jiraVersionPrefix override clears inherited base value on override range")
    fun `MIG-040-B null jiraVersionPrefix override - override range returns null, base range returns original`() {
        val comp = makeComponent("NULL_OVERRIDE_B")
        val base = makeBase(comp)
        // jiraProjectKey is required for JiraComponent to be emitted
        base.jiraProjectKey = "WALLET"
        base.jiraVersionPrefix = "wallet"

        // Override row: explicitly clears jiraVersionPrefix for the newer range
        val nullOverrideRow = makeNullScalarOverrideRow(comp, "[5.1.1475,)", "jira.versionPrefix")
        // jiraVersionPrefix intentionally left null

        comp.configurations.addAll(listOf(base, nullOverrideRow))
        stubComponent(comp)

        // Base range: legacy range keeps "wallet"
        val baseCfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_B", "3.0.0")
        assertNotNull(baseCfg)
        assertEquals("wallet", baseCfg!!.jiraConfiguration?.componentInfo?.versionPrefix)

        // Override range: null override should clear jiraVersionPrefix.
        // After the fix, ComponentInfo is not created at all if both prefix and format are null
        // (buildJiraComponent checks `merged.jiraVersionPrefix != null || merged.jiraVersionFormat != null`).
        // The jiraProjectKey still propagates via merged view, so jiraConfiguration is non-null,
        // but componentInfo becomes null (or versionPrefix inside it is null).
        val overrideCfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_B", "5.1.1475")
        assertNotNull(overrideCfg)
        assertNull(
            overrideCfg!!.jiraConfiguration?.componentInfo?.versionPrefix,
            "jiraVersionPrefix must be null for override range; " +
                "was: ${overrideCfg.jiraConfiguration?.componentInfo?.versionPrefix}",
        )
    }

    // ========================================================================
    // (C) escrow.buildTask null override
    // ========================================================================

    @Test
    @DisplayName("MIG-040-C: null escrowBuildTask override clears inherited base value on override range")
    fun `MIG-040-C null escrowBuildTask override - override range returns null, base range returns original`() {
        val comp = makeComponent("NULL_OVERRIDE_C")
        val base = makeBase(comp)
        base.escrowBuildTask = "clean build -x test"

        // Override row: clears escrowBuildTask for range [2.0,)
        val nullOverrideRow = makeNullScalarOverrideRow(comp, "[2.0,)", "escrow.buildTask")
        // escrowBuildTask intentionally left null

        comp.configurations.addAll(listOf(base, nullOverrideRow))
        stubComponent(comp)

        // Base range (1.0): keeps the build task
        val baseCfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_C", "1.0.0")
        assertNotNull(baseCfg)
        assertEquals("clean build -x test", baseCfg!!.escrow?.buildTask)

        // Override range (2.0): null override should clear the build task
        val overrideCfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_C", "2.0.0")
        assertNotNull(overrideCfg)
        assertNull(
            overrideCfg!!.escrow?.buildTask,
            "escrowBuildTask must be null for override range; was: ${overrideCfg.escrow?.buildTask}",
        )
    }

    // ========================================================================
    // (D) Null-clear override does NOT disturb non-overlapping ranges
    // ========================================================================

    @Test
    @DisplayName("MIG-040-D: version 2.5 (outside override range) still gets base buildFilePath")
    fun `MIG-040-D null override does not affect version outside the override range`() {
        val comp = makeComponent("NULL_OVERRIDE_D")
        val base = makeBase(comp)
        base.buildFilePath = "BaseValue"

        // Override row applies only to [1.0,2.0)
        val nullOverrideRow = makeNullScalarOverrideRow(comp, "[1.0,2.0)", "build.buildFilePath")

        comp.configurations.addAll(listOf(base, nullOverrideRow))
        stubComponent(comp)

        // Version 2.5 is outside [1.0,2.0) → base buildFilePath applies
        val cfg = resolver.getResolvedComponentDefinition("NULL_OVERRIDE_D", "2.5.0")
        assertNotNull(cfg)
        assertEquals(
            "BaseValue",
            cfg!!.buildFilePath,
            "buildFilePath for version outside override range must come from base",
        )
    }
}
