package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
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
import org.octopusden.releng.versions.NumericVersion
import org.octopusden.releng.versions.VersionRange
import org.apache.maven.artifact.DefaultArtifact
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.nio.file.Paths
import java.util.Properties
import javax.annotation.Resource
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

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
    private val versionNames = VersionNames(componentsRegistryProperties.versionName.serviceBranch,
        componentsRegistryProperties.versionName.service,
        componentsRegistryProperties.versionName.minor)

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
        val  builder = JiraComponentVersion.builder(jiraComponentVersionFormatter)
            .component(range.jiraComponent)
            .componentVersionByNameAndVersion(range.componentName, version)
        return builder.build()!!
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
        val builder = JiraComponentVersion.builder(jiraComponentVersionFormatter)
        val factory = VersionRangeFactory(versionNames)
        for (jiraComponentVersionRange in jiraComponentVersionRanges) {

            val versionRange = factory.create(jiraComponentVersionRange.versionRange)

            val versionIterator = mutableVersionSet.iterator()
            while (versionIterator.hasNext()) {

                val version = versionIterator.next()

                val numericArtifactVersion = numericVersionFactory.create(version)
                if (versionRange.containsVersion(numericArtifactVersion)) {
                    builder.component(jiraComponentVersionRange.jiraComponent)
                        .componentVersionByNameAndVersion(jiraComponentVersionRange.componentName, version)
                    val jiraComponentVersion = builder.build()
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
            val buildVersion = this.component.componentVersionFormat.buildVersionFormat.formatVersion(numericVersionFactory, this.version)
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
            ?: throw NotFoundException("Component id $projectKey:$version is not found")
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
                    it.value.find { it.distribution != null }?.distribution?.GAV() ?: "",
                    it.value.find { it.distribution != null }?.distribution?.securityGroups
                        ?: SecurityGroups(null)
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
        val builder = JiraComponentVersion.builder(jiraComponentVersionFormatter)
        val foundRanges = jiraComponentVersionRanges.map { item ->
            val versionRange = versionRangeFactory.create(item.versionRange)
            val numericArtifactVersion = numericVersionFactory.create(version)
            if (versionRange.containsVersion(numericArtifactVersion)) {
                builder.component(item.jiraComponent)
                    .componentVersion(ComponentVersion.create(item.componentName, version))
                val jiraComponentVersion = builder.build()
                if (jiraComponentVersionFormatter.matchesAny(jiraComponentVersion, version, strict)) {
                    if (LOG.isTraceEnabled) {
                        LOG.trace("Found {} component by {}:{}", jiraComponentVersion, key, version)
                    }
                    return@map item
                }
            }
            return@map null

        }.filterNotNull()
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
    ): JiraComponentVersion? {
        val range = getJiraComponentVersionRange(key, version, keyToVersionRangeMap, strict)
        return JiraComponentVersion.builder(jiraComponentVersionFormatter)
            .component(range.jiraComponent)
            .componentVersion(ComponentVersion.create(range.componentName, version))
            .build()
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
