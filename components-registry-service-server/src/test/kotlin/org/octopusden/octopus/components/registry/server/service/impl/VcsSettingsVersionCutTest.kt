package org.octopusden.octopus.components.registry.server.service.impl

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.entity.ComponentConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.resolvers.BuildToolsResolver
import org.octopusden.octopus.escrow.resolvers.JiraParametersResolver
import org.octopusden.octopus.escrow.resolvers.ModuleByArtifactResolver
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * RES-027: the VCS tag of a version keeps every part of the requested version.
 *
 * Shape: release format `$major.$minor.$service`, major and line formats `$major`, tag
 * `$module-$version`. Non-strict normalization matches "2.2" only by the major format and
 * cuts it to "2", and "2" formatted with the build (= release) format is "2.0.0" — a tag
 * that does not exist. Components get this shape when their release format gains a part
 * after versions were released with the shorter one.
 *
 * The same cases run through the Git resolver (real DSL loader over a fixture) and the
 * DB resolver (entity graph with a mocked repository), so both stay in step. Plain unit
 * test — no Spring/DB context.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class VcsSettingsVersionCutTest {
    private val versionNames = VersionNames("serviceCBranch", "serviceC", "minorC")
    private val numericVersionFactory = NumericVersionFactory(versionNames)
    private val versionRangeFactory = VersionRangeFactory(versionNames)

    private val gitResolver: ComponentRegistryResolverImpl by lazy { buildGitResolver() }
    private val dbResolver: DatabaseComponentRegistryResolver by lazy { buildDbResolver() }

    @ParameterizedTest(name = "git: {0} {1} -> {2}")
    @CsvSource(textBlock = CASES)
    @DisplayName("RES-027: Git resolver: the VCS tag keeps every part of the requested version")
    fun `RES-027 git resolver tag`(
        component: String,
        version: String,
        expectedTag: String,
    ) {
        assertEquals(expectedTag, tagOf(gitResolver, component, version))
    }

    @ParameterizedTest(name = "db: {0} {1} -> {2}")
    @CsvSource(textBlock = CASES)
    @DisplayName("RES-027: DB resolver: the VCS tag keeps every part of the requested version")
    fun `RES-027 db resolver tag`(
        component: String,
        version: String,
        expectedTag: String,
    ) {
        assertEquals(expectedTag, tagOf(dbResolver, component, version))
    }

    private fun tagOf(
        resolver: ComponentRegistryResolver,
        component: String,
        version: String,
    ): String? =
        resolver
            .getVCSSettings(component, version)
            .versionControlSystemRoots
            .single()
            .tag

    private fun buildGitResolver(): ComponentRegistryResolverImpl {
        val loader =
            EscrowConfigurationLoader(
                ConfigLoader(
                    ComponentRegistryInfo.fromClassPath("vcs-version-cut/ParentComponent.groovy"),
                    versionNames,
                    ProductTypes.values().associateWith { it.name },
                ),
                listOf("org.octopusden.octopus"),
                listOf("NONE", "CLASSIC", "ALFA"),
                versionNames,
                Files.createTempDirectory("vcs-version-cut-copyrights"),
            )
        val properties =
            ComponentsRegistryProperties(
                groovyPath = Files.createTempDirectory("vcs-version-cut-groovy").toString(),
                workDir = "",
                mainGroovyFile = "ParentComponent.groovy",
                dependencyMappingFile = null,
                vcs = ComponentsRegistryProperties.VcsSettings(root = null, username = null),
                versionName =
                    ComponentsRegistryProperties.VersionNameSettings(
                        versionNames.serviceBranch,
                        versionNames.service,
                        versionNames.minor,
                    ),
                copyrightPath = null,
            )
        return ComponentRegistryResolverImpl(
            loader,
            JiraParametersResolver(loader, emptyMap()),
            JiraComponentVersionFormatter(versionNames),
            ModuleByArtifactResolver(versionNames),
            BuildToolsResolver(),
            properties,
            numericVersionFactory,
            versionRangeFactory,
            SimpleMeterRegistry(),
            mutableMapOf(),
        ).apply { updateCache() }
    }

    private fun buildDbResolver(): DatabaseComponentRegistryResolver {
        val componentRepository = mock(ComponentRepository::class.java)
        val components =
            listOf(
                componentEntity(COMPONENT, "PARENT", hotfixVersionFormat = null, hotfixBranch = null),
                componentEntity(HOTFIX_COMPONENT, "HOTFIX", "\$major.\$minor.\$service-\$fix", "hotfix"),
            )
        components.forEach { `when`(componentRepository.findByComponentKey(it.componentKey)).thenReturn(it) }
        `when`(componentRepository.findAll()).thenReturn(components.toMutableList())
        return DatabaseComponentRegistryResolver(
            componentRepository,
            mock(DependencyMappingRepository::class.java),
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )
    }

    private fun componentEntity(
        key: String,
        projectKey: String,
        hotfixVersionFormat: String?,
        hotfixBranch: String?,
    ): ComponentEntity {
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = key)
        val base =
            ComponentConfigurationEntity(
                component = component,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = false,
                buildSystem = "MAVEN",
                jiraProjectKey = projectKey,
                deprecated = false,
            )
        base.jiraMinorVersionFormat = "\$major"
        base.jiraLineVersionFormat = "\$major"
        base.jiraReleaseVersionFormat = "\$major.\$minor.\$service"
        base.jiraHotfixVersionFormat = hotfixVersionFormat
        base.vcsEntries.add(
            VcsSettingsEntryEntity(
                componentConfiguration = base,
                vcsPath = "ssh://git@example/$key",
                name = "main",
                tag = "\$module-\$version",
                hotfixBranch = hotfixBranch,
                sortOrder = 0,
            ),
        )
        component.configurations.add(base)
        return component
    }

    companion object {
        private const val COMPONENT = "parentComponent"
        private const val HOTFIX_COMPONENT = "hotfixComponent"
        private const val ALL_VERSIONS = "(,0),[0,)"

        // component, requested version -> expected tag. parentComponent: the first three are
        // shorter than the build format and normalization cuts them; the rest: a full release
        // version as is, a longer version trimmed to the build format, zero-only dropped parts and
        // a major version padded to it. hotfixComponent (hotfix format $major.$minor.$service-$fix,
        // hotfix branch set): a hotfix version as is, a release version by the build format, and a
        // cut version kept with hotfix enabled too.
        private const val CASES = """parentComponent, 2.2,     parentComponent-2.2
            parentComponent, 5.3,     parentComponent-5.3
            parentComponent, 6.15,    parentComponent-6.15
            parentComponent, 2.2.1,   parentComponent-2.2.1
            parentComponent, 2.2.1.5, parentComponent-2.2.1
            parentComponent, 2.0,     parentComponent-2.0.0
            parentComponent, 2,       parentComponent-2.0.0
            hotfixComponent, 2.2.1-3, hotfixComponent-2.2.1-3
            hotfixComponent, 2.2.1,   hotfixComponent-2.2.1
            hotfixComponent, 2.2,     hotfixComponent-2.2"""
    }
}
