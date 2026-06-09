package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.DefaultConfigParameters
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig

/**
 * MIG-047 RED test: `emitMarkerOverrides` must create a GROUP_ARTIFACT_PATTERN MARKER
 * row when the override config has a different `groupIdPattern` or `artifactIdPattern`
 * than the base — even when neither base nor override carries an explicit `distribution.GAV()`.
 *
 * Bug shape (prod-vs-schema-v2 compat regression):
 *   Component DSL has two version ranges:
 *     - base   `[1.0,1.1)` : groupId = "com.example",    artifactId = "alpha-fixture"
 *     - override `[1.1,)` : groupId = "com.example.ic", artifactId = "alpha-fixture"
 *   Neither range declares an explicit `distribution { gav = … }` block.
 *
 * Pre-fix behaviour:
 *   `emitMarkerOverrides(base=[1.0,1.1), override=[1.1,))` compares
 *   `distribution.GAV()` on both sides.  Both are null → `mavenArtifactsDiffer`
 *   returns false → no MARKER is emitted for `[1.1,)`.
 *   Consequence: `getMavenArtifactParameters` falls back to `componentLevelFallback`
 *   (from base config) for ALL ranges, so `[1.1,)` wrongly returns
 *   `com.example` instead of `com.example.ic`.
 *
 * Post-fix behaviour:
 *   `emitMarkerOverrides` additionally detects `groupIdPattern` / `artifactIdPattern`
 *   divergence and emits a GROUP_ARTIFACT_PATTERN MARKER whose `mavenArtifacts` rows
 *   are synthetic entries built from `override.groupIdPattern` / `override.artifactIdPattern`.
 *   Unlike DISTRIBUTION_MAVEN, GROUP_ARTIFACT_PATTERN is excluded from MarkerAttributes.ALL
 *   so it does not affect `getAllJiraComponentVersionRanges`.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG047PerRangeGroupIdImportTest {

    private lateinit var service: ImportServiceImpl
    private lateinit var configurationRepository: ComponentConfigurationRepository
    private lateinit var emitMarkerOverridesMethod: Method

    @BeforeEach
    fun setUp() {
        configurationRepository = mock(ComponentConfigurationRepository::class.java)

        // saveMarkerRowWithChildren uses two repository methods:
        //   1. findByComponentIdAndVersionRangeAndOverriddenAttribute → returns ComponentConfigurationEntity?
        //      Default mock behavior returns null (no existing row) — no stub needed.
        //   2. save(row) — return value is NOT used by saveMarkerRowWithChildren (returns `row` directly).
        //      Default mock behavior returns null for reference-return methods — safe to leave unstubbed.

        // emitMarkerOverrides accesses commonDefaultsToolsCache which calls
        // configurationLoader.loadCommonDefaults(emptyMap()). The mock default returns
        // null which causes NPE. Stub it to return an empty DefaultConfigParameters
        // (buildParameters=null → tools chain returns null → emptyList() fallback).
        val configurationLoader = mock(EscrowConfigurationLoader::class.java)
        val emptyDefaults = mock(DefaultConfigParameters::class.java)
        doReturn(emptyDefaults).`when`(configurationLoader).loadCommonDefaults(emptyMap())

        service = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = configurationLoader,
            configSyncService = mock(ConfigSyncService::class.java),
            componentRepository = mock(ComponentRepository::class.java),
            configurationRepository = configurationRepository,
            componentGroupRepository = mock(ComponentGroupRepository::class.java),
            systemRepository = mock(SystemRepository::class.java),
            toolRepository = mock(ToolRepository::class.java),
            labelRepository = mock(LabelRepository::class.java),
            componentLabelRepository = mock(ComponentLabelRepository::class.java),
            componentRequiredToolRepository = mock(ComponentRequiredToolRepository::class.java),
            componentBuildToolBeanRepository = mock(ComponentBuildToolBeanRepository::class.java),
        )

        emitMarkerOverridesMethod = ImportServiceImpl::class.java.getDeclaredMethod(
            "emitMarkerOverrides",
            ComponentEntity::class.java,
            ComponentConfigurationEntity::class.java,
            EscrowModuleConfig::class.java,
            EscrowModuleConfig::class.java,
        )
        emitMarkerOverridesMethod.isAccessible = true
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeConfig(
        groupId: String,
        artifactId: String,
        versionRange: String? = null,
    ): EscrowModuleConfig {
        val cfg = EscrowModuleConfig()
        setGroovyField(cfg, "groupIdPattern", groupId)
        setGroovyField(cfg, "artifactIdPattern", artifactId)
        if (versionRange != null) setGroovyField(cfg, "versionRange", versionRange)
        // distribution deliberately left null — no explicit distribution.gav in DSL
        return cfg
    }

    private fun setGroovyField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun callEmitMarkerOverrides(
        component: ComponentEntity,
        savedBase: ComponentConfigurationEntity,
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): List<ComponentConfigurationEntity> =
        emitMarkerOverridesMethod.invoke(service, component, savedBase, base, override)
            as List<ComponentConfigurationEntity>

    // =========================================================================
    // MIG-047-001 RED: groupIdPattern change per range, no distribution.GAV
    // =========================================================================

    @Test
    @DisplayName(
        "MIG-047-001: emitMarkerOverrides creates GROUP_ARTIFACT_PATTERN MARKER " +
            "when groupIdPattern differs across ranges (no distribution.GAV on either side)",
    )
    fun `MIG-047-001 GROUP_ARTIFACT_PATTERN MARKER created when groupId differs per range without distribution gav`() {
        // synthetic component entity with a stable UUID so saveMarkerRowWithChildren
        // can invoke component.id!!
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "alpha-fixture")

        val savedBase = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[1.0,1.1)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

        // base config: range [1.0,1.1), group=com.example, artifact=alpha-fixture, distribution=null
        val baseConfig = makeConfig("com.example", "alpha-fixture", "[1.0,1.1)")

        // override config: range [1.1,), group=com.example.ic (different!), artifact=alpha-fixture, distribution=null
        val overrideConfig = makeConfig("com.example.ic", "alpha-fixture", "[1.1,)")

        val result = callEmitMarkerOverrides(component, savedBase, baseConfig, overrideConfig)

        // MIG-047 assertion: a GROUP_ARTIFACT_PATTERN MARKER must be emitted for [1.1,)
        // even though distribution.GAV() is null on both sides.
        //
        // Pre-fix: result is empty (mavenArtifactsDiffer(null, null) = false → no MARKER).
        // Post-fix: result contains one GROUP_ARTIFACT_PATTERN MARKER with com.example.ic.
        assertEquals(1, result.size, "Expected exactly one MARKER row for the [1.1,) override range")

        val marker = result[0]
        assertEquals("MARKER", marker.rowType, "Row type must be MARKER")
        assertEquals(
            MarkerAttributes.GROUP_ARTIFACT_PATTERN,
            marker.overriddenAttribute,
            "MIG-047 path must emit a GROUP_ARTIFACT_PATTERN marker (not distribution.maven)",
        )
        assertEquals("[1.1,)", marker.versionRange, "Marker must be on the [1.1,) range")

        // The marker must carry a synthetic maven artifact row for the override's group/artifact
        assertEquals(
            1,
            marker.mavenArtifacts.size,
            "MARKER must have one synthetic maven artifact row for the override groupId/artifactId",
        )
        val mavenRow = marker.mavenArtifacts[0]
        assertEquals(
            "com.example.ic",
            mavenRow.groupPattern,
            "Maven artifact row groupPattern must be override groupIdPattern",
        )
        assertEquals(
            "alpha-fixture",
            mavenRow.artifactPattern,
            "Maven artifact row artifactPattern must be override artifactIdPattern",
        )
    }

    @Test
    @DisplayName(
        "MIG-047-002: emitMarkerOverrides creates GROUP_ARTIFACT_PATTERN MARKER " +
            "when artifactIdPattern differs across ranges (no distribution.GAV on either side)",
    )
    fun `MIG-047-002 GROUP_ARTIFACT_PATTERN MARKER created when artifactId CSV differs per range without distribution gav`() {
        // bug-shape: artifactId adds a new token in newer range (same group)
        // base [1.0,): group=com.example.widgets, artifact=core-a,core-b,core-c
        // override [2.0,): group=com.example.widgets, artifact=core-a,core-b,core-c,core-tcp-client
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "beta-fixture")
        val savedBase = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[1.0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

        val baseConfig = makeConfig("com.example.widgets", "core-a,core-b,core-c", "[1.0,)")
        val overrideConfig = makeConfig("com.example.widgets", "core-a,core-b,core-c,core-tcp-client", "[2.0,)")

        val result = callEmitMarkerOverrides(component, savedBase, baseConfig, overrideConfig)

        assertEquals(1, result.size, "Expected one GROUP_ARTIFACT_PATTERN MARKER for [2.0,)")
        val marker = result[0]
        assertEquals(MarkerAttributes.GROUP_ARTIFACT_PATTERN, marker.overriddenAttribute)
        assertEquals("[2.0,)", marker.versionRange)

        // The marker must carry 4 maven artifact rows (one per CSV token) in sort order
        assertEquals(4, marker.mavenArtifacts.size, "Marker must have one row per artifactId token")
        val patterns = marker.mavenArtifacts.sortedBy { it.sortOrder }.map { it.artifactPattern }
        assertEquals(listOf("core-a", "core-b", "core-c", "core-tcp-client"), patterns)
        marker.mavenArtifacts.forEach { row ->
            assertEquals("com.example.widgets", row.groupPattern, "All rows must have the same groupPattern")
        }
    }

    @Test
    @DisplayName(
        "MIG-047-003: when per-range distribution.GAV AND per-range artifactId both differ from base, " +
            "emit BOTH a DISTRIBUTION_MAVEN MARKER and a GROUP_ARTIFACT_PATTERN MARKER (V1 contract)",
    )
    fun `MIG-047-003 emit both markers when both diff conditions hold`() {
        // V1-contract: per-range DSL overrides for `distribution { gav = … }` AND `artifactId`
        // are orthogonal and must surface independently in the schema-v2 view. The previous
        // assertion ("exactly one MARKER, not two") encoded a mutual-exclusion bug:
        // `emitMarkerOverrides` used `if … else if …` so only DISTRIBUTION_MAVEN was emitted
        // for the both-diff case. The read-path at /maven-artifacts then fell back to the
        // inherited component-level artifactId instead of the per-range V1 value.
        // After the fix, both markers must be present.
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "gamma-fixture")
        val savedBase = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[1.0,)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

        // Build a Distribution with an explicit GAV so that mavenArtifactsDiffer returns true
        val baseDist = org.octopusden.octopus.escrow.model.Distribution(
            true, false,
            "com.example:gamma-lib:jar",
            null, null, null, null,
        )
        val overrideDist = org.octopusden.octopus.escrow.model.Distribution(
            true, false,
            "com.example.new:gamma-lib-v2:jar",
            null, null, null, null,
        )

        val baseConfig = makeConfig("com.example", "gamma-lib", "[1.0,)")
        setGroovyField(baseConfig, "distribution", baseDist)

        val overrideConfig = makeConfig("com.example.new", "gamma-lib-v2", "[2.0,)")
        setGroovyField(overrideConfig, "distribution", overrideDist)

        val result = callEmitMarkerOverrides(component, savedBase, baseConfig, overrideConfig)

        assertEquals(2, result.size, "Must emit BOTH a DISTRIBUTION_MAVEN and a GROUP_ARTIFACT_PATTERN marker")
        val attributes = result.map { it.overriddenAttribute }.toSet()
        assertEquals(
            setOf(MarkerAttributes.DISTRIBUTION_MAVEN, MarkerAttributes.GROUP_ARTIFACT_PATTERN),
            attributes,
            "Both marker kinds must be present (different read-paths consume them)",
        )
    }

    @Test
    @DisplayName(
        "MIG-047-004: per-range artifactId='single-dist' AND distribution.GAV " +
            "emits a GROUP_ARTIFACT_PATTERN marker carrying the per-range artifactId for /maven-artifacts",
    )
    fun `MIG-047-004 single-token per-range artifactId emits GROUP_ARTIFACT_PATTERN`() {
        // DSL shape:
        //   "fixture-X" {
        //       groupId = "com.example.fixture"
        //       artifactId = "sci_build|sci_utils|fixture_builder"   // top-level CSV fallback
        //       "[6.22, )" {
        //           artifactId = "single-dist"                       // per-range override
        //           distribution { gav = "com.example.fixture:single-dist:zip" }
        //       }
        //   }
        //
        // V1-contract: /maven-artifacts for [6.22, ) returns artifactPattern="single-dist"
        // (per-range artifactId), NOT the inherited top-level CSV.
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "fixture-X-single-dist")
        val savedBase = ComponentConfigurationEntity(
            id = UUID.randomUUID(),
            component = component,
            versionRange = "[5.1, 6.22)",
            overriddenAttribute = null,
            rowType = "BASE",
        )

        val baseDist = org.octopusden.octopus.escrow.model.Distribution(
            true, false,
            null,  // base has no GAV
            null, null, null, null,
        )
        val overrideDist = org.octopusden.octopus.escrow.model.Distribution(
            true, false,
            "com.example.fixture:single-dist:zip",
            null, null, null, null,
        )

        val baseConfig = makeConfig("com.example.fixture", "sci_build|sci_utils|fixture_builder", "[5.1, 6.22)")
        setGroovyField(baseConfig, "distribution", baseDist)

        val overrideConfig = makeConfig("com.example.fixture", "single-dist", "[6.22, )")
        setGroovyField(overrideConfig, "distribution", overrideDist)

        val result = callEmitMarkerOverrides(component, savedBase, baseConfig, overrideConfig)

        assertEquals(2, result.size, "Both DISTRIBUTION_MAVEN and GROUP_ARTIFACT_PATTERN markers must be emitted")
        val gap = result.firstOrNull { it.overriddenAttribute == MarkerAttributes.GROUP_ARTIFACT_PATTERN }
        assertNotNull(gap, "GROUP_ARTIFACT_PATTERN marker missing (V1-contract gap)")
        assertEquals(1, gap!!.mavenArtifacts.size, "Single-token artifactId override must produce one marker row")
        val artifact = gap.mavenArtifacts.first()
        assertEquals("com.example.fixture", artifact.groupPattern)
        assertEquals("single-dist", artifact.artifactPattern)
    }
}
