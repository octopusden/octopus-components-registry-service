package org.octopusden.octopus.components.registry.server.service.impl

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.apache.maven.artifact.DefaultArtifact
import org.jetbrains.kotlin.utils.keysToMap
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.config.ComponentsRegistryProperties
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.EscrowConfigValidator
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
import java.nio.file.Paths
import java.util.EnumMap
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.annotation.PostConstruct
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
    private val meterRegistry: MeterRegistry,
    @Resource(name = "dependencyMapping") private val dependencyMapping: MutableMap<String, String>,
    private var imageToComponentMap: Map<String, String>,
    private val dockerCacheLock: ReentrantReadWriteLock = ReentrantReadWriteLock()
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
        updateMetrics()
        loadImageToComponentMap()
    }

    override fun getComponents() = configuration.escrowModules.values

    override fun getComponentById(id: String) = getComponents().find { it.moduleName == id }

    override fun getResolvedComponentDefinition(id: String, version: String): EscrowModuleConfig? {
        return EscrowConfigurationLoader.getEscrowModuleConfig(configuration, ComponentVersion.create(id, version))
    }

    private fun reconstructVerstionString(imageTag : String): String {
        val numericVersionFactory = NumericVersionFactory(versionNames)
        val version = numericVersionFactory.create(imageTag)
        val originalDelimeters = imageTag.filter { it == '.' || it == '-' || it == '_' }
        var versionString = ""
        for (i in 0 until version.itemsCount) {
            versionString += version.getItem(i).toString()
            if (i < (version.itemsCount - 1)) {
                versionString += (originalDelimeters.getOrNull(i) ?: originalDelimeters.firstOrNull() ?: ".")
            }
        }
        return versionString
    }

    private fun extractSuffix(versionString: String, tagWithVersion: String): String? {
        return if (tagWithVersion.startsWith(versionString)) {
            val suffix = tagWithVersion.removePrefix(versionString)
                .removePrefix(".").removePrefix("-").removePrefix("_")
            suffix.ifEmpty { null }
        } else {
            null
        }
    }

    override fun getJiraComponentVersion(component: String, version: String) =
        getJiraComponentVersionToRangeByComponentAndVersion(component, version).first

    override fun getJiraComponentVersions(
        component: String, versions: List<String>
    ) = try {
        versions.keysToMap { version ->
            getJiraComponentVersionsToRanges(
                version, getJiraComponentVersionRangesByComponent(component), false
            ).map { it.first }
        }.filterValues { it.isNotEmpty() }.mapValues { it.value.first() }
    } catch (_: NotFoundException) {
        emptyMap()
    }

    override fun getVCSSettings(component: String, version: String): VCSSettings {
        val (jiraComponentVersion, jiraComponentVersionRange) =
            getJiraComponentVersionToRangeByComponentAndVersion(component, version)
        val buildVersion = jiraComponentVersion.component.componentVersionFormat.buildVersionFormat.formatVersion(
            numericVersionFactory, jiraComponentVersion.version
        )
        return ModelConfigPostProcessor(ComponentVersion.create(component, buildVersion), versionNames)
            .resolveVariables(jiraComponentVersionRange.vcsSettings)
    }

    override fun getJiraComponentByProjectAndVersion(projectKey: String, version: String) =
        getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version).first

    override fun getJiraComponentsByProject(projectKey: String) =
        getJiraComponentVersionRangesByProject(projectKey).map { it.componentName }.toSet()

    override fun getJiraComponentVersionRangesByProject(projectKey: String) =
        jiraParametersResolver.componentConfig.projectKeyToJiraComponentVersionRangeMap[projectKey]?.toSet()
            ?: throw NotFoundException("Project '$projectKey' is not found")

    override fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution> {
        return getJiraComponentVersionRangesByProject(projectKey)
            .groupBy { it.componentName }
            .mapValues { (_, versionRange) ->
                Distribution(
                    versionRange.any { it.distribution?.explicit() ?: true },
                    versionRange.any { it.distribution?.external() ?: false },
                    versionRange.find { it.distribution != null }?.distribution?.GAV(),
                    versionRange.find { it.distribution != null }?.distribution?.DEB(),
                    versionRange.find { it.distribution != null }?.distribution?.RPM(),
                    versionRange.find { it.distribution != null }?.distribution?.docker(),
                    versionRange.find { it.distribution != null }?.distribution?.securityGroups
                        ?: SecurityGroups(null),
                )
            }
    }

    override fun getVCSSettingForProject(projectKey: String, version: String): VCSSettings {
        val jiraComponentVersionRange = getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version).second
        //TODO: should version be transformed to buildVersion in the same way as in getVCSSettings method?
        return ModelConfigPostProcessor(
            ComponentVersion.create(
                jiraComponentVersionRange.componentName,
                version
            ), versionNames
        ).resolveVariables(jiraComponentVersionRange.vcsSettings)
    }

    override fun getDistributionForProject(projectKey: String, version: String): Distribution =
        getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version).second.distribution

    override fun getAllJiraComponentVersionRanges() =
        jiraParametersResolver.componentConfig.projectKeyToJiraComponentVersionRangeMap.values.flatten().toSet()

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
        LOG.debug("Get components count by build system")
        val result = getComponents()
            .filterNot { it.isArchived() }
            .fold(EnumMap<BuildSystem, Int>(BuildSystem::class.java)) { acc, component ->
                val buildSystem = component.getBuildSystem()
                acc[buildSystem] = acc.getOrDefault(buildSystem, 0) + 1
                acc
            }
        LOG.debug("Components count by build system: {}", result)
        return result
    }

    override fun findComponentsByDockerImages(images: Set<Image>): Set<ComponentImage> {
        dockerCacheLock.readLock().lock()
        return try {
            val imageNames = images.map { it.name }.toSet()
            imageToComponentMap.filterKeys(imageNames::contains).mapNotNull { (imgName, component) ->
                images.find { it.name == imgName }?.let { requiredImage ->
                    findConfigurationByImage(imgName, requiredImage.tag, component)
                }
            }.toSet()
        } finally {
            dockerCacheLock.readLock().unlock()
        }
    }

    private fun findConfigurationByImage(imageName: String, imageTag: String, compId: String): ComponentImage? {
            val versionString = reconstructVerstionString(imageTag)
            val tagSuffix = extractSuffix(versionString, imageTag)
            val ecl = EscrowConfigurationLoader.getEscrowModuleConfig(configuration, ComponentVersion.create(compId, versionString))


            if (ecl?.distribution?.docker() != null) {
                val dockerString = ecl.distribution?.docker() ?: return null
                // add check for tag suffix if any vs dockerString
                if ( imageName in dockerStringToList(dockerString) ) {
                    val declToCheck = imageName + (tagSuffix?.let { ":$it" } ?: "")
                    if (dockerString.split(',').contains(declToCheck)) {
                        return ComponentImage(compId, versionString, Image(imageName, imageTag))
                    }
                }
            }
            return null
    }

    private fun dockerStringToList(dockerString: String): List<String> {
        return dockerString.split(",").map { it.substringBefore(":") }
    }

    private fun buildImageToComponentMap(): Map<String, String> {
        val component2DockerMap = mutableMapOf<String, String>()
        getComponents().forEach { comp ->
            comp.moduleConfigurations.forEach { moduleConfig ->
                moduleConfig.distribution?.docker()?.let { dockersString ->
                    dockerStringToList(dockersString).forEach { dockerImgName ->
                        component2DockerMap[dockerImgName] = comp.moduleName
                    }
                }
            }
        }
        return component2DockerMap
    }

    private fun EscrowModule.getBuildSystem(): BuildSystem {
        return moduleConfigurations.firstOrNull()?.let { it.buildSystem.toDTO() }
            ?: throw IllegalStateException("No module configurations available")
    }

    private fun EscrowModule.isArchived() = moduleConfigurations.firstOrNull()?.componentDisplayName
        ?.endsWith(EscrowConfigValidator.ARCHIVED_SUFFIX, ignoreCase = true) ?: false

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

    private fun updateMetrics() {
        LOG.info("Update metrics build system count")
        val componentCounts = getComponentsCountByBuildSystem()

        BuildSystem.values().forEach { buildSystem ->
            buildSystemMetrics[buildSystem] = componentCounts[buildSystem] ?: 0
        }
    }

    private fun loadImageToComponentMap() {
        LOG.info("Update dockerImageMapping")
        dockerCacheLock.writeLock().lock()
        try {
            imageToComponentMap = buildImageToComponentMap()
        } finally {
            dockerCacheLock.writeLock().unlock()
        }
    }

    private val buildSystemMetrics = ConcurrentHashMap<BuildSystem, Int>()

    @PostConstruct
    fun initBuildSystemMetrics() {
        BuildSystem.values().forEach { buildSystem ->
            buildSystemMetrics[buildSystem] = 0

            Gauge.builder("components.buildsystem.count") {
                buildSystemMetrics[buildSystem] ?: 0
            }
                .tag("buildSystem", buildSystem.name)
                .register(meterRegistry)
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

    /**
     * Returns single JiraComponentVersion to JiraComponentVersionRange pair found by Component ID and Version
     *
     * @param component Component Id
     * @param version   Version (having non-strict build/release/major/line format, i.e. having enough of significant numeric elements)
     * @return JiraComponentVersion to JiraComponentVersionRange pair
     */
    private fun getJiraComponentVersionToRangeByComponentAndVersion(
        component: String, version: String
    ) = getJiraComponentVersionsToRanges(
        version, getJiraComponentVersionRangesByComponent(component), false
    ).let {
        when (it.size) {
            1 -> it.first()
            0 -> throw NotFoundException("Version '$version' for component '$component' is not found")
            else -> throw IllegalStateException("Found ${it.size} configurations for version '$version' of component $component")
        }
    }

    private fun getJiraComponentVersionRangesByComponent(component: String) =
        jiraParametersResolver.componentConfig.componentNameToJiraComponentVersionRangeMap[component]?.toSet()
            ?: throw NotFoundException("Component '$component' is not found")

    /**
     * Returns single JiraComponentVersion to JiraComponentVersionRange pair found by Jira Project Key and Jira Version
     *
     * @param projectKey Jira Project Key
     * @param version    Jira Version (having strict build/release/major/line format with prefix if it's configured, release candidate suffix is allowed)
     * @return JiraComponentVersion to JiraComponentVersionRange pair
     */
    private fun getJiraComponentVersionToRangeByProjectAndVersion(
        projectKey: String, version: String
    ) = getJiraComponentVersionsToRanges(
        version, getJiraComponentVersionRangesByProject(projectKey)
    ).let {
        when (it.size) {
            1 -> it.first()
            0 -> throw NotFoundException("Version '$version' for project '$projectKey' is not found")
            else -> throw IllegalStateException("Found ${it.size} configurations for version '$version' of project $projectKey")
        }
    }

    private fun getJiraComponentVersionsToRanges(
        version: String, versionRanges: Set<JiraComponentVersionRange>, strict: Boolean = true
    ) = with(numericVersionFactory.create(version)) {
        versionRanges.filter { versionRangeFactory.create(it.versionRange).containsVersion(this) }
            .mapNotNull { versionRange ->
                val component = versionRange.jiraComponentVersion.component
                when {
                    jiraComponentVersionFormatter.matchesBuildVersionFormat(component, version, strict) ->
                        formatVersion(component.componentVersionFormat.buildVersionFormat)

                    jiraComponentVersionFormatter.matchesReleaseVersionFormat(component, version, strict)
                            || jiraComponentVersionFormatter.matchesRCVersionFormat(component, version, strict) ->
                        formatVersion(component.componentVersionFormat.releaseVersionFormat)

                    jiraComponentVersionFormatter.matchesMajorVersionFormat(component, version, strict) ->
                        formatVersion(component.componentVersionFormat.majorVersionFormat)

                    jiraComponentVersionFormatter.matchesLineVersionFormat(component, version, strict) ->
                        formatVersion(component.componentVersionFormat.lineVersionFormat)

                    jiraComponentVersionFormatter.matchesHotfixVersionFormat(component, version, strict) ->
                        formatVersion(component.componentVersionFormat.hotfixVersionFormat)

                    else -> null
                }?.let { cleanVersion ->
                    JiraComponentVersion(
                        ComponentVersion.create(versionRange.componentName, cleanVersion),
                        versionRange.component,
                        jiraComponentVersionFormatter
                    ) to versionRange
                }
            }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComponentRegistryResolverImpl::class.java)
    }
}
