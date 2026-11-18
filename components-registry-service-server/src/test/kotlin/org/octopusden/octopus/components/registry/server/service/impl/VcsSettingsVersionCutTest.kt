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

    @ParameterizedTest(name = "git: {0} -> {1}")
    @CsvSource(textBlock = CASES)
    @DisplayName("RES-027: Git resolver: the VCS tag keeps every part of the requested version")
    fun `RES-027 git resolver tag`(
        version: String,
        expectedTag: String,
    ) {
        assertEquals(expectedTag, tagOf(gitResolver, version))
    }

    @ParameterizedTest(name = "db: {0} -> {1}")
    @CsvSource(textBlock = CASES)
    @DisplayName("RES-027: DB resolver: the VCS tag keeps every part of the requested version")
    fun `RES-027 db resolver tag`(
        version: String,
        expectedTag: String,
    ) {
        assertEquals(expectedTag, tagOf(dbResolver, version))
    }

    private fun tagOf(
        resolver: ComponentRegistryResolver,
        version: String,
    ): String? =
        resolver
            .getVCSSettings(COMPONENT, version)
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
        val component = ComponentEntity(id = UUID.randomUUID(), componentKey = COMPONENT)
        val base =
            ComponentConfigurationEntity(
                component = component,
                versionRange = ALL_VERSIONS,
                overriddenAttribute = null,
                rowType = "BASE",
                isSyntheticBase = false,
                buildSystem = "MAVEN",
                jiraProjectKey = "PARENT",
                deprecated = false,
            )
        base.jiraMinorVersionFormat = "\$major"
        base.jiraLineVersionFormat = "\$major"
        base.jiraReleaseVersionFormat = "\$major.\$minor.\$service"
        base.vcsEntries.add(
            VcsSettingsEntryEntity(
                componentConfiguration = base,
                vcsPath = "ssh://git@example/parent",
                name = "main",
                tag = "\$module-\$version",
                sortOrder = 0,
            ),
        )
        component.configurations.add(base)
        `when`(componentRepository.findByComponentKey(COMPONENT)).thenReturn(component)
        `when`(componentRepository.findAll()).thenReturn(mutableListOf(component))
        return DatabaseComponentRegistryResolver(
            componentRepository,
            mock(DependencyMappingRepository::class.java),
            numericVersionFactory,
            versionRangeFactory,
            versionNames,
        )
    }

    companion object {
        private const val COMPONENT = "parentComponent"
        private const val ALL_VERSIONS = "(,0),[0,)"

        // requested version -> expected tag. The first three are shorter than the build format
        // and normalization cuts them; the rest: a full release version as is, a longer version
        // trimmed to the build format, zero-only dropped parts and a major version padded to it.
        private const val CASES = """2.2,     parentComponent-2.2
            5.3,     parentComponent-5.3
            6.15,    parentComponent-6.15
            2.2.1,   parentComponent-2.2.1
            2.2.1.5, parentComponent-2.2.1
            2.0,     parentComponent-2.0.0
            2,       parentComponent-2.0.0"""
    }
}
