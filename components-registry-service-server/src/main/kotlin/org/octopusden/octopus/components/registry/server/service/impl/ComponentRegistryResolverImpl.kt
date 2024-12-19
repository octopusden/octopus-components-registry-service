package org.octopusden.octopus.components.registry.server.service.impl

import java.nio.file.Paths
import java.util.Properties
import javax.annotation.Resource
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import org.apache.maven.artifact.DefaultArtifact
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.resolvers.JiraParametersResolver
import org.octopusden.octopus.escrow.resolvers.ModuleByArtifactResolver
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.util.EnumMap

@Service
@EnableConfigurationProperties(ComponentsRegistryProperties::class)
class ComponentRegistryResolverImpl(
    private val configurationLoader: EscrowConfigurationLoader,
    private val jiraParametersResolver: JiraParametersResolver,
    private val jiraComponentVersionFormatter: JiraComponentVersionFormatter,
    private val moduleByArtifactResolver: ModuleByArtifactResolver,
    private val componentsRegistryProperties: ComponentsRegistryProperties,
    private val numericVersionFactory: NumericVersionFactory,
    private val versionRangeFactory: VersionRangeFactory,
    @Resource(name = "dependencyMapping") private val dependencyMapping: MutableMap<String, String>
) : ComponentRegistryResolver {

    private lateinit var configuration: EscrowConfiguration
    private val versionNames = VersionNames(
        componentsRegistryProperties.versionName.serviceBranch,
        componentsRegistryProperties.versionName.service,
        componentsRegistryProperties.versionName.minor
    )

    override fun updateCache() {
        configuration = configurationLoader.loadFullConfigurationWithoutValidationForUnknownAttributes(emptyMap())
        jiraParametersResolver.setEscrowConfiguration(configuration)
        moduleByArtifactResolver.setEscrowConfiguration(configuration)
        loadDependencyMapping()
    }

    override fun getComponents() = configuration.escrowModules.values

    override fun getComponentById(id: String) = getComponents().find { it.moduleName == id }

    override fun getResolvedComponentDefinition(id: String, version: String): EscrowModuleConfig? {
        return EscrowConfigurationLoader.getEscrowModuleConfig(configuration, ComponentVersion.create(id, version))
    }

    override fun getJiraComponentVersion(component: String, version: String): JiraComponentVersion {
        val keyToVersionRanges = jiraParametersResolver.componentConfig.componentNameToJiraComponentVersionRangeMap
        val range = getJiraComponentVersionRange(component, version, keyToVersionRanges, false)
        return getJiraComponentVersion(range, version)
    }

    override fun getJiraComponentVersions(
        component: String,
        versions: List<String>
    ): Map<String, JiraComponentVersion> {
        val jiraComponentVersionRanges =
            jiraParametersResolver.componentConfig.componentNameToJiraComponentVersionRangeMap[component]
                ?: return emptyMap()

        val result = mutableMapOf<String, JiraComponentVersion>()

        val mutableVersionSet = versions.toMutableSet()
        val factory = VersionRangeFactory(versionNames)
        for (jiraComponentVersionRange in jiraComponentVersionRanges) {

            val versionRange = factory.create(jiraComponentVersionRange.versionRange)

            val versionIterator = mutableVersionSet.iterator()
            while (versionIterator.hasNext()) {

                val version = versionIterator.next()

                val numericArtifactVersion = numericVersionFactory.create(version)
                if (versionRange.containsVersion(numericArtifactVersion)) {
                    val jiraComponentVersion = getJiraComponentVersion(jiraComponentVersionRange, version)
                    if (jiraComponentVersionFormatter.matchesAny(jiraComponentVersion, version, false)) {
                        jiraComponentVersionRange?.let {
                            result[version] = jiraComponentVersion
                            versionIterator.remove()
                        }
                    }
                }
            }
            if (mutableVersionSet.isEmpty()) {
                break
            }
        }
        return result
    }

    override fun getVCSSettings(component: String, version: String): VCSSettings {
        return with(getJiraComponentVersion(component, version)) {
            val buildVersion = this.component.componentVersionFormat.buildVersionFormat.formatVersion(
                numericVersionFactory,
                this.version
            )
            getJiraComponentVersionRange(component, buildVersion)
                .let {
                    ModelConfigPostProcessor(
                        ComponentVersion.create(
                            component,
                            buildVersion
                        ), versionNames
                    ).resolveVariables(it.vcsSettings)
                }
        }
    }

    override fun getJiraComponentByProjectAndVersion(projectKey: String, version: String): JiraComponentVersion {
        val projectKeyToJiraComponentVersionRangeMap =
            jiraParametersResolver.componentConfig.projectKeyToJiraComponentVersionRangeMap
        return getJiraComponentVersion(projectKey, version, projectKeyToJiraComponentVersionRangeMap)
    }

    override fun getJiraComponentsByProject(projectKey: String): Set<String> {
        return getProjectJiraComponentVersionRanges(projectKey)
            .map { it.componentName }
            .toSet()
    }

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange> {
        return getProjectJiraComponentVersionRanges(projectKey)
            .toSet()
    }

    override fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution> {
        return getProjectJiraComponentVersionRanges(projectKey)
            .groupBy { it.componentName }
            .mapValues {
                Distribution(
                    it.value.any { it.distribution?.explicit() ?: true },
                    it.value.any { it.distribution?.external() ?: false },
                    it.value.find { it.distribution != null }?.distribution?.GAV(),
                    it.value.find { it.distribution != null }?.distribution?.DEB(),
                    it.value.find { it.distribution != null }?.distribution?.RPM(),
                    it.value.find { it.distribution != null }?.distribution?.docker(),
                    it.value.find { it.distribution != null }?.distribution?.securityGroups
                        ?: SecurityGroups(null),
                )
            }
    }

    private fun getProjectJiraComponentVersionRanges(projectKey: String): MutableList<JiraComponentVersionRange> =
        jiraParametersResolver.componentConfig
            .projectKeyToJiraComponentVersionRangeMap[projectKey]
            ?: throw NotFoundException(projectKey)

    override fun getVCSSettingForProject(projectKey: String, version: String): VCSSettings {
        val projectKeyToJiraComponentVersionRangeMap =
            jiraParametersResolver.componentConfig.projectKeyToJiraComponentVersionRangeMap
        return getVCSFromMap(projectKey, version, projectKeyToJiraComponentVersionRangeMap)
    }

    override fun getDistributionForProject(projectKey: String, version: String): Distribution {
        val projectKeyToJiraComponentVersionRangeMap = jiraParametersResolver.componentConfig
            .projectKeyToJiraComponentVersionRangeMap
        return getDistribution(projectKey, projectKeyToJiraComponentVersionRangeMap, version)
    }

    override fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRange> {
        return jiraParametersResolver.componentConfig
            .projectKeyToJiraComponentVersionRangeMap
            .values
            .flatten()
            .toSet()
    }

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent {
        return findComponentByArtifactOrNull(artifact)
            ?: throw NotFoundException("No component found for artifact=$artifact")
    }

    override fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?> {
        LOG.debug("Find components by artifacts: {}", artifacts)
        return artifacts.associateWith { artifact ->
            findComponentByArtifactOrNull(artifact)
        }
    }

    override fun getMavenArtifactParameters(component: String): Map<String, ComponentArtifactConfiguration> {
        return jiraParametersResolver.getMavenArtifactParameters(component)
    }

    override fun getDependencyMapping(): Map<String, String> {
        return dependencyMapping
    }

    /**
     * Get components count by build system
     */
    override fun getComponentsCountByBuildSystem(): EnumMap<BuildSystem, Int> {
        val components = getComponents()
        val result = EnumMap<BuildSystem, Set<String>>(BuildSystem::class.java)
        components.forEach { component ->
            component.moduleConfigurations.forEach { moduleConfig ->
                if (moduleConfig.componentDisplayName?.endsWith("(archived)") == true) {
                    return@forEach
                }
                val buildSystem = moduleConfig.buildSystem.toDTO()
                result[buildSystem] = result.getOrDefault(buildSystem, emptySet()).plus(component.moduleName)
            }
        }
        return result.map { (key, value) -> key to value.size }.toMap(EnumMap(BuildSystem::class.java))
    }

    private fun org.octopusden.octopus.escrow.BuildSystem.toDTO(): BuildSystem {
        return when (this) {
            org.octopusden.octopus.escrow.BuildSystem.BS2_0 -> BuildSystem.BS2_0
            org.octopusden.octopus.escrow.BuildSystem.MAVEN -> BuildSystem.MAVEN
            org.octopusden.octopus.escrow.BuildSystem.ECLIPSE_MAVEN -> BuildSystem.ECLIPSE_MAVEN
            org.octopusden.octopus.escrow.BuildSystem.GRADLE -> BuildSystem.GRADLE
            org.octopusden.octopus.escrow.BuildSystem.WHISKEY -> BuildSystem.WHISKEY
            org.octopusden.octopus.escrow.BuildSystem.PROVIDED -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_NOT_SUPPORTED -> BuildSystem.NOT_SUPPORTED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_PROVIDED_MANUALLY -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.GOLANG -> BuildSystem.GOLANG
        }
    }

    private fun findComponentByArtifactOrNull(artifact: ArtifactDependency): VersionedComponent? =
        moduleByArtifactResolver.resolveComponentByArtifact(
            DefaultArtifact(
                artifact.group,
                artifact.name,
                artifact.version,
                null,
                "jar",
                "N/A",
                null
            )
        )?.let { resolvedComponent ->
            VersionedComponent(
                resolvedComponent.componentName,
                null,
                resolvedComponent.version,
                ""
            )
        }

    private fun getJiraComponentVersionRange(
        key: String, version: String,
        keyToVersionRanges: Map<String, List<JiraComponentVersionRange>>,
        strict: Boolean = true
    ): JiraComponentVersionRange {
        val jiraComponentVersionRanges =
            keyToVersionRanges[key] ?: throw NotFoundException("Component id $key is not found")
        val foundRanges = jiraComponentVersionRanges.mapNotNull { item ->
            val versionRange = versionRangeFactory.create(item.versionRange)
            val numericArtifactVersion = numericVersionFactory.create(version)
            if (versionRange.containsVersion(numericArtifactVersion)) {
                val jiraComponentVersion = getJiraComponentVersion(item, version)
                if (jiraComponentVersionFormatter.matchesAny(jiraComponentVersion, version, strict)) {
                    if (LOG.isTraceEnabled) {
                        LOG.trace("Found {} component by {}:{}", jiraComponentVersion, key, version)
                    }
                    return@mapNotNull item
                }
            }
            return@mapNotNull null
        }
        check(foundRanges.size <= 1) { "Found several configurations for $key:$version: ${foundRanges.map { "${it.componentName}:${it.versionRange}" }}" }
        return foundRanges.firstOrNull() ?: throw NotFoundException("Component id $key:$version is not found")
    }

    private fun getJiraComponentVersionRange(component: String, version: String): JiraComponentVersionRange {
        val keyToVersionRanges = jiraParametersResolver.componentConfig.componentNameToJiraComponentVersionRangeMap
        return getJiraComponentVersionRange(component, version, keyToVersionRanges, false)
    }

    private fun getJiraComponentVersion(
        key: String, version: String,
        keyToVersionRangeMap: Map<String, List<JiraComponentVersionRange>>,
        strict: Boolean = true
    ): JiraComponentVersion {
        val range = getJiraComponentVersionRange(key, version, keyToVersionRangeMap, strict)
        return getJiraComponentVersion(range, version)
    }

    private fun getJiraComponentVersion(
        range: JiraComponentVersionRange,
        version: String
    ): JiraComponentVersion {
        val component = range.jiraComponentVersion.component

        val resultVersion = when {
            jiraComponentVersionFormatter.matchesBuildVersionFormat(
                component,
                version,
                true
            ) -> numericVersionFactory.create(version)
                .formatVersion(component.componentVersionFormat.buildVersionFormat)

            jiraComponentVersionFormatter.matchesReleaseVersionFormat(
                component,
                version,
                true
            ) -> numericVersionFactory.create(version)
                .formatVersion(component.componentVersionFormat.releaseVersionFormat)

            jiraComponentVersionFormatter.matchesMajorVersionFormat(
                component,
                version,
                true
            ) -> numericVersionFactory.create(version)
                .formatVersion(component.componentVersionFormat.majorVersionFormat)

            jiraComponentVersionFormatter.matchesLineVersionFormat(
                component,
                version,
                true
            ) -> numericVersionFactory.create(version).formatVersion(component.componentVersionFormat.lineVersionFormat)

            else -> version
        }

        return JiraComponentVersion(
            ComponentVersion.create(range.componentName, resultVersion),
            range.component,
            jiraComponentVersionFormatter
        )
    }

    private fun getDistribution(
        name: String, map: MutableMap<String, MutableList<JiraComponentVersionRange>>,
        version: String
    ): Distribution {
        return getJiraComponentVersionRange(name, version, map).distribution
    }

    private fun getVCSFromMap(
        name: String,
        version: String,
        map: MutableMap<String, MutableList<JiraComponentVersionRange>>
    ): VCSSettings {
        val jiraComponentVersionRange = getJiraComponentVersionRange(name, version, map)
        return jiraComponentVersionRange.let {
            ModelConfigPostProcessor(
                ComponentVersion.create(
                    it.componentName,
                    version
                ), versionNames
            ).resolveVariables(it.vcsSettings)
        }
    }

    private fun loadDependencyMapping() {
        dependencyMapping.clear()
        val mappingProperties = Properties()
        val groovyPath = Paths.get(componentsRegistryProperties.groovyPath)
        componentsRegistryProperties.dependencyMappingFile
            ?.let { dependencyMappingFileName ->
                val dependencyMappingPath = groovyPath.resolve(dependencyMappingFileName)
                if (dependencyMappingPath.isRegularFile() && dependencyMappingPath.exists()) {
                    dependencyMappingPath.inputStream()
                        .use { inputStream ->
                            mappingProperties.load(inputStream)
                        }
                } else {
                    LOG.warn("Dependency Mapping file {} is not regular or doesn't exist", dependencyMappingFileName)
                }
            }
        mappingProperties.entries.forEach { (alias, component) ->
            dependencyMapping[alias.toString()] = component.toString()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComponentRegistryResolverImpl::class.java)!!
    }
}
