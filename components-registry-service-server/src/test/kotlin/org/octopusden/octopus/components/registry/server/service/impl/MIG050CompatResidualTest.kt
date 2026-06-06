package org.octopusden.octopus.components.registry.server.service.impl

import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionDockerImageEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionMavenArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.mapper.ALL_VERSIONS
import org.octopusden.octopus.components.registry.server.mapper.MarkerAttributes
import org.octopusden.octopus.components.registry.server.repository.ComponentBuildToolBeanRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentConfigurationRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentGroupRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentLabelRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRequiredToolRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentSourceRepository
import org.octopusden.octopus.components.registry.server.repository.LabelRepository
import org.octopusden.octopus.components.registry.server.repository.RegistryConfigRepository
import org.octopusden.octopus.components.registry.server.repository.SystemRepository
import org.octopusden.octopus.components.registry.server.repository.ToolRepository
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.DefaultConfigParameters
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.escrow.RepositoryType
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

/**
 * MIG-050 — close remaining jira-component-version-ranges compat residuals:
 * import inherit semantics for absent override blocks, and explicit-range VCS
 * registry isolation from components.vcs_external_registry.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class MIG050CompatResidualTest {

    private val componentRepository: ComponentRepository = mock(ComponentRepository::class.java)
    private val dependencyMappingRepository: DependencyMappingRepository =
        mock(DependencyMappingRepository::class.java)
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)
    private lateinit var resolver: DatabaseComponentRegistryResolver
    private lateinit var importService: ImportServiceImpl
    private lateinit var emitMarkerOverridesMethod: Method

    @BeforeEach
    fun setUp() {
        resolver = DatabaseComponentRegistryResolver(
            componentRepository,
            dependencyMappingRepository,
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )

        val configurationLoader = mock(EscrowConfigurationLoader::class.java)
        val emptyDefaults = mock(DefaultConfigParameters::class.java)
        doReturn(emptyDefaults).`when`(configurationLoader).loadCommonDefaults(emptyMap())

        val configurationRepository = mock(ComponentConfigurationRepository::class.java)
        `when`(configurationRepository.save(any(ComponentConfigurationEntity::class.java)))
            .thenAnswer { invocation ->
                val row = invocation.arguments[0] as ComponentConfigurationEntity
                if (row.id == null) {
                    row.id = UUID.randomUUID()
                }
                row
            }

        importService = ImportServiceImpl(
            gitResolver = mock(ComponentRegistryResolverImpl::class.java),
            dbResolver = mock(DatabaseComponentRegistryResolver::class.java),
            componentSourceRepository = mock(ComponentSourceRepository::class.java),
            sourceRegistry = mock(ComponentSourceRegistry::class.java),
            configurationLoader = configurationLoader,
            registryConfigRepository = mock(RegistryConfigRepository::class.java),
            componentRepository = componentRepository,
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

    private fun setGroovyField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun makeConfig(
        versionRange: String,
        distribution: Distribution? = null,
        vcsSettings: VCSSettings? = null,
    ): EscrowModuleConfig {
        val cfg = EscrowModuleConfig()
        setGroovyField(cfg, "versionRange", versionRange)
        setGroovyField(cfg, "distribution", distribution)
        setGroovyField(cfg, "vcsSettings", vcsSettings)
        return cfg
    }

    @Suppress("UNCHECKED_CAST")
    private fun callEmitMarkerOverrides(
        component: ComponentEntity,
        baseRow: ComponentConfigurationEntity,
        base: EscrowModuleConfig,
        override: EscrowModuleConfig,
    ): List<ComponentConfigurationEntity> =
        emitMarkerOverridesMethod.invoke(importService, component, baseRow, base, override)
            as List<ComponentConfigurationEntity>

    private fun stubComponent(component: ComponentEntity) {
        `when`(componentRepository.findByComponentKey(component.componentKey)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
    }

    @Test
    @DisplayName("MIG-050-001: absent override distribution does not emit distribution.maven marker")
    fun `MIG-050-001 absent override distribution does not emit distribution maven marker`() {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "ancs-empty-range-fixture")
        val baseRow =
            ComponentConfigurationEntity(
                component = component,
                versionRange = "[1.0,2.0)",
                rowType = "BASE",
            )
        val baseDist =
            Distribution(
                true,
                true,
                "com.example:artifact:zip",
                null,
                null,
                "analytics/example-distribution",
                null,
            )
        val baseConfig = makeConfig("[1.0,2.0)", distribution = baseDist)
        val overrideConfig = makeConfig("[2.0,)")

        val rows = callEmitMarkerOverrides(component, baseRow, baseConfig, overrideConfig)

        assertTrue(
            rows.none { it.overriddenAttribute == MarkerAttributes.DISTRIBUTION_MAVEN },
            "Null override distribution is inherit semantics — must not emit clearing marker",
        )
        assertTrue(
            rows.none { it.overriddenAttribute == MarkerAttributes.DISTRIBUTION_DOCKER },
            "Null override distribution is inherit semantics — must not emit docker marker",
        )
    }

    @Test
    @DisplayName("MIG-050-002: absent override vcsSettings does not emit vcs.settings marker")
    fun `MIG-050-002 absent override vcsSettings does not emit vcs settings marker`() {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = "vcs-inherit-fixture")
        val baseRow =
            ComponentConfigurationEntity(
                component = component,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
            )
        val baseVcs =
            VCSSettings.create(
                "ssh://base-registry",
                listOf(
                    VersionControlSystemRoot.create(
                        "main",
                        RepositoryType.GIT,
                        "ssh://base-root",
                        null,
                        null,
                        null,
                    ),
                ),
            )
        val baseConfig = makeConfig(ALL_VERSIONS, vcsSettings = baseVcs)
        val overrideConfig = makeConfig("[2.0,)")

        val rows = callEmitMarkerOverrides(component, baseRow, baseConfig, overrideConfig)

        assertTrue(
            rows.isEmpty(),
            "Null override vcsSettings is inherit semantics — must not emit vcs marker or registry scalar",
        )
    }

    @Test
    @DisplayName("MIG-050-003: explicit RANGE_PRESENCE range inherits base distribution on jira-ranges")
    fun `MIG-050-003 explicit RANGE_PRESENCE range inherits base distribution on jira ranges`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "ancs-inherit-dist-fixture").apply {
                jiraDisplayName = "Analytics Sub"
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "[1.0,2.0)",
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "ANCS",
            ).apply {
                isSyntheticBase = true
                mavenArtifacts.add(
                    DistributionMavenArtifactEntity(
                        componentConfiguration = this,
                        groupPattern = "com.example",
                        artifactPattern = "distribution",
                        sortOrder = 0,
                    ),
                )
            }
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[1.0,2.0)",
                    rowType = "RANGE_PRESENCE",
                ),
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[2.0,)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val ranges = resolver.getJiraComponentVersionRangesByProject("ANCS")

        assertEquals(
            "com.example:distribution",
            ranges.first { it.versionRange == "[2.0,)" }.distribution?.GAV(),
            "Empty `[2,)` DSL block inherits component distribution on jira-ranges",
        )
    }

    @Test
    @DisplayName("MIG-050-004: explicit range does not inherit components.vcs_external_registry")
    fun `MIG-050-004 explicit range does not inherit components vcs external registry`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "cards-vcs-registry-fixture").apply {
                vcsExternalRegistry = "ssh://component-default"
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "CARDS",
            ).apply {
                vcsEntries.add(
                    VcsSettingsEntryEntity(
                        componentConfiguration = this,
                        name = "main",
                        vcsPath = "ssh://base-root",
                        sortOrder = 0,
                    ),
                )
            }
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[1.0,2.0)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val range = resolver.getJiraComponentVersionRangesByProject("CARDS").first { it.versionRange == "[1.0,2.0)" }

        assertNull(
            range.vcsSettings.externalRegistry,
            "Explicit range must not fall back to components.vcs_external_registry",
        )
        assertEquals(1, range.vcsSettings.versionControlSystemRoots.size)
    }

    @Test
    @DisplayName("MIG-050-005: RANGE_PRESENCE range with jira config inherits component jira.displayName")
    fun `MIG-050-005 RANGE_PRESENCE range inherits component jira displayName`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "c-pipes-kernel-fixture").apply {
                jiraDisplayName = "C-Pipes Kernel"
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "CARDS",
            )
        comp.configurations.addAll(
            listOf(
                base,
                ComponentConfigurationEntity(
                    component = comp,
                    versionRange = "[03.63.00,)",
                    rowType = "RANGE_PRESENCE",
                ),
            ),
        )
        stubComponent(comp)

        val range = resolver.getJiraComponentVersionRangesByProject("CARDS")
            .first { it.versionRange == "[03.63.00,)" }

        assertEquals(
            "C-Pipes Kernel",
            range.component.displayName,
            "Explicit range must inherit components.jira_display_name when BASE row has no per-range displayName",
        )
    }

    @Test
    @DisplayName("MIG-050-006: docker-only distribution marker inherits component explicit/external")
    fun `MIG-050-006 docker-only distribution marker inherits component explicit external`() {
        val comp =
            ComponentEntity(id = UUID.randomUUID(), componentKey = "docker-inherit-fixture").apply {
                distributionExplicit = false
                distributionExternal = true
            }
        val base =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = ALL_VERSIONS,
                rowType = "BASE",
                buildSystem = "MAVEN",
                deprecated = false,
                jiraProjectKey = "TEST_DOCKER",
            )
        val dockerMarker =
            ComponentConfigurationEntity(
                component = comp,
                versionRange = "(1.0.0, 2.0.0)",
                overriddenAttribute = MarkerAttributes.DISTRIBUTION_DOCKER,
                rowType = "MARKER",
            ).apply {
                dockerImages.add(
                    DistributionDockerImageEntity(
                        componentConfiguration = this,
                        imageName = "test-docker-4",
                        flavor = "amd64",
                        sortOrder = 0,
                    ),
                )
            }
        comp.configurations.addAll(listOf(base, dockerMarker))
        stubComponent(comp)

        val range = resolver.getJiraComponentVersionRangesByProject("TEST_DOCKER")
            .first { it.versionRange == "(1.0.0, 2.0.0)" }

        assertEquals(false, range.distribution?.explicit())
        assertEquals(true, range.distribution?.external())
        assertEquals("test-docker-4:amd64", range.distribution?.docker())
    }
}
