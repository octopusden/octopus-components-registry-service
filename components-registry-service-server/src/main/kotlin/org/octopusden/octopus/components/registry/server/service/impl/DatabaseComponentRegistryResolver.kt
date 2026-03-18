package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentVersionEntity
import org.octopusden.octopus.components.registry.server.mapper.toDistribution
import org.octopusden.octopus.components.registry.server.mapper.toEscrowModule
import org.octopusden.octopus.components.registry.server.mapper.toEscrowModuleConfig
import org.octopusden.octopus.components.registry.server.mapper.toJiraComponent
import org.octopusden.octopus.components.registry.server.mapper.toVCSSettings
import org.octopusden.octopus.components.registry.server.repository.ComponentArtifactIdRepository
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.repository.FieldOverrideRepository
import org.octopusden.octopus.components.registry.server.repository.JiraComponentConfigRepository
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.escrow.MavenArtifactMatcher
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.config.JiraComponentVersionRangeFactory
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.resolvers.ComponentHotfixSupportResolver
import org.octopusden.octopus.releng.JiraComponentVersionFormatter
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.EnumMap

@Suppress("TooManyFunctions")
@Service("databaseComponentRegistryResolver")
@Transactional(readOnly = true)
class DatabaseComponentRegistryResolver(
    private val componentRepository: ComponentRepository,
    private val jiraComponentConfigRepository: JiraComponentConfigRepository,
    private val componentArtifactIdRepository: ComponentArtifactIdRepository,
    private val dependencyMappingRepository: DependencyMappingRepository,
    private val fieldOverrideRepository: FieldOverrideRepository,
    private val numericVersionFactory: NumericVersionFactory,
    private val versionRangeFactory: VersionRangeFactory,
    private val versionNames: VersionNames,
) : ComponentRegistryResolver {
    private val jiraComponentVersionFormatter = JiraComponentVersionFormatter(versionNames)
    private val jiraComponentVersionRangeFactory = JiraComponentVersionRangeFactory(versionNames)
    private val componentHotfixSupportResolver = ComponentHotfixSupportResolver()

    override fun updateCache() {
        // No-op: DB resolver always reads fresh data from the database
        log.debug("updateCache() called on DatabaseComponentRegistryResolver — no-op")
    }

    override fun getComponents(): MutableCollection<EscrowModule> =
        componentRepository.findAll().map { it.toEscrowModule() }.toMutableList()

    override fun getComponentById(id: String): EscrowModule? = componentRepository.findByName(id)?.toEscrowModule()

    override fun getResolvedComponentDefinition(
        id: String,
        version: String,
    ): EscrowModuleConfig? {
        val component = componentRepository.findByName(id) ?: return null
        val matchedVersion = findMatchingVersionConfig(component, version)

        // Version-specific-only component (no ALL_VERSIONS default): return null when no range matches
        // Identified by: has version entities AND no component-level jira configs
        if (matchedVersion == null && component.versions.isNotEmpty() && component.jiraComponentConfigs.isEmpty()) {
            return null
        }

        val config = component.toEscrowModuleConfig(matchedVersion)
        // Normalize version before calculateDistribution (mirrors Git resolver: uses jiraComponentVersion.version)
        if (config.distribution != null) {
            val normalizedVersion =
                try {
                    getJiraComponentVersionToRangeByComponentAndVersion(id, version).first.version
                } catch (_: Exception) {
                    version
                }
            val resolved =
                org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
                    .calculateDistribution(config.distribution, normalizedVersion)
            val field = EscrowModuleConfig::class.java.getDeclaredField("distribution")
            field.isAccessible = true
            field.set(config, resolved)
        }
        return applyOverrides(id, config, version)
    }

    override fun getJiraComponentVersion(
        component: String,
        version: String,
    ): JiraComponentVersion {
        val jiraVersionRanges = getJiraComponentVersionRangesByComponent(component)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
        return when (results.size) {
            1 -> results.first().first
            0 -> throw NotFoundException("Version '$version' for component '$component' is not found")
            else ->
                error(
                    "Found ${results.size} configurations for version '$version' of component '$component'",
                )
        }
    }

    override fun getJiraComponentVersions(
        component: String,
        versions: List<String>,
    ): Map<String, JiraComponentVersion> =
        try {
            val jiraVersionRanges = getJiraComponentVersionRangesByComponent(component)
            versions
                .associateWith { version ->
                    getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
                        .map { it.first }
                }.filterValues { it.isNotEmpty() }
                .mapValues { it.value.first() }
        } catch (_: NotFoundException) {
            emptyMap()
        }

    override fun getVCSSettings(
        component: String,
        version: String,
    ): VCSSettings {
        val (jiraComponentVersion, jiraComponentVersionRange) =
            getJiraComponentVersionToRangeByComponentAndVersion(component, version)
        val versionFormat = jiraComponentVersion.component.componentVersionFormat
        val defaultBuildVersion =
            versionFormat.buildVersionFormat.formatVersion(
                numericVersionFactory,
                jiraComponentVersion.version,
            )
        val buildVersion =
            if (jiraComponentVersionRange.component.isHotfixEnabled) {
                val hotfixVersion =
                    versionFormat.hotfixVersionFormat.formatVersion(
                        numericVersionFactory,
                        jiraComponentVersion.version,
                    )
                if (hotfixVersion == jiraComponentVersion.version) hotfixVersion else defaultBuildVersion
            } else {
                defaultBuildVersion
            }
        return ModelConfigPostProcessor(ComponentVersion.create(component, buildVersion), versionNames)
            .resolveVariables(jiraComponentVersionRange.vcsSettings)
    }

    override fun getBuildTools(
        component: String,
        version: String,
        ignoreRequired: Boolean?,
    ): List<BuildTool> {
        val buildConfiguration = getResolvedComponentDefinition(component, version)?.buildConfiguration ?: return emptyList()
        val resolvedBuildTools = mutableListOf<BuildTool>()

        if (ignoreRequired != true && buildConfiguration.requiredProject) {
            resolvedBuildTools +=
                PTCProductToolBean().apply {
                    setVersion(buildConfiguration.projectVersion)
                }
        }

        buildConfiguration.buildTools?.let(resolvedBuildTools::addAll)
        return resolvedBuildTools
    }

    override fun getJiraComponentByProjectAndVersion(
        projectKey: String,
        version: String,
    ): JiraComponentVersion = getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version).first

    override fun getJiraComponentsByProject(projectKey: String): Set<String> =
        getJiraComponentVersionRangesByProject(projectKey)
            .map {
                it.componentName
            }.toSet()

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange> {
        val configs = jiraComponentConfigRepository.findByProjectKey(projectKey)
        if (configs.isEmpty()) {
            throw NotFoundException("Project '$projectKey' is not found")
        }
        return configs
            .mapNotNull { config ->
                val componentName = config.component?.name ?: config.componentVersion?.component?.name ?: return@mapNotNull null
                val versionRange = config.componentVersion?.versionRange ?: ALL_VERSIONS
                val jiraComponent = config.toJiraComponent()
                val vcsSettingsEntity =
                    if (config.componentVersion != null) {
                        config.componentVersion!!.vcsSettings.firstOrNull()
                            ?: config.component?.vcsSettings?.firstOrNull()
                    } else {
                        config.component?.vcsSettings?.firstOrNull()
                    }
                val vcsSettings = vcsSettingsEntity?.toVCSSettings() ?: VCSSettings.create(null, emptyList())
                val distributionEntity =
                    if (config.componentVersion != null) {
                        config.componentVersion!!.distributions.firstOrNull()
                            ?: config.component?.distributions?.firstOrNull()
                    } else {
                        config.component?.distributions?.firstOrNull()
                    }
                val distribution = distributionEntity?.toDistribution()
                buildJiraComponentVersionRange(componentName, versionRange, jiraComponent, distribution, vcsSettings)
            }.toSet()
    }

    override fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution> =
        getJiraComponentVersionRangesByProject(projectKey)
            .groupBy { it.componentName }
            .mapValues { (_, versionRanges) ->
                Distribution(
                    versionRanges.any { it.distribution?.explicit() ?: true },
                    versionRanges.any { it.distribution?.external() ?: false },
                    versionRanges.find { it.distribution != null }?.distribution?.GAV(),
                    versionRanges.find { it.distribution != null }?.distribution?.DEB(),
                    versionRanges.find { it.distribution != null }?.distribution?.RPM(),
                    versionRanges.find { it.distribution != null }?.distribution?.docker(),
                    versionRanges.find { it.distribution != null }?.distribution?.securityGroups
                        ?: SecurityGroups(null),
                )
            }

    override fun getVCSSettingForProject(
        projectKey: String,
        version: String,
    ): VCSSettings {
        val (_, jiraComponentVersionRange) =
            getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version)
        return ModelConfigPostProcessor(
            ComponentVersion.create(jiraComponentVersionRange.componentName, version),
            versionNames,
        ).resolveVariables(jiraComponentVersionRange.vcsSettings)
    }

    override fun getDistributionForProject(
        projectKey: String,
        version: String,
    ): Distribution {
        log.info("Get distribution for project: {} version: {}", projectKey, version)
        val found = getJiraComponentVersionToRangeByProjectAndVersion(projectKey, version)
        return org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
            .calculateDistribution(found.second.distribution, found.first.version)
    }

    override fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRange> =
        componentRepository
            .findAll()
            .flatMap { component ->
                buildJiraVersionRangesForComponent(component)
            }.toSet()

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        findComponentByArtifactOrNull(artifact)
            ?: throw NotFoundException("No component found for artifact=$artifact")

    override fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?> {
        log.debug("Find components by artifacts: {}", artifacts)
        return artifacts.associateWith { artifact -> findComponentByArtifactOrNull(artifact) }
    }

    override fun getMavenArtifactParameters(component: String): Map<String, ComponentArtifactConfiguration> {
        val componentEntity =
            componentRepository.findByName(component)
                ?: throw NotFoundException("Component '$component' is not found")

        // Git resolver keys by versionRangeString; component-level artifacts use ALL_VERSIONS
        val result = mutableMapOf<String, ComponentArtifactConfiguration>()
        for (artifactId in componentEntity.artifactIds) {
            result[ALL_VERSIONS] = ComponentArtifactConfiguration(artifactId.groupPattern, artifactId.artifactPattern)
        }

        // Version-specific artifact IDs (fall back to component-level if version has none)
        for (version in componentEntity.versions) {
            val versionArtifactId = version.artifactIds.firstOrNull()
            val effectiveArtifactId = versionArtifactId ?: componentEntity.artifactIds.firstOrNull()
            if (effectiveArtifactId != null) {
                result[version.versionRange] =
                    ComponentArtifactConfiguration(
                        effectiveArtifactId.groupPattern,
                        effectiveArtifactId.artifactPattern,
                    )
            }
        }

        return result
    }

    override fun getDependencyMapping(): Map<String, String> =
        dependencyMappingRepository.findAll().associate { it.alias to it.componentName }

    override fun getComponentsCountByBuildSystem(): EnumMap<BuildSystem, Int> {
        log.debug("Get components count by build system")
        val result =
            getComponents()
                .filterNot { it.isArchived() }
                .fold(EnumMap<BuildSystem, Int>(BuildSystem::class.java)) { acc, component ->
                    val buildSystem = component.getBuildSystem()
                    acc[buildSystem] = acc.getOrDefault(buildSystem, 0) + 1
                    acc
                }
        log.debug("Components count by build system: {}", result)
        return result
    }

    override fun getComponentProductMapping(): Map<String, ProductTypes> =
        componentRepository
            .findAll()
            .filter { it.productType != null }
            .mapNotNull { component ->
                val productType =
                    try {
                        ProductTypes.valueOf(component.productType!!)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                productType?.let { component.name to it }
            }.toMap()

    override fun findComponentsByDockerImages(images: Set<Image>): Set<ComponentImage> {
        val imageNames = images.map { it.name }.toSet()
        return buildImageToComponentMap()
            .filterKeys(imageNames::contains)
            .mapNotNull { (imgName, compId) ->
                images.find { it.name == imgName }?.let { requiredImage ->
                    findConfigurationByDockerImage(imgName, requiredImage.tag, compId)
                }
            }.toSet()
    }

    // ============================================================
    // Private helper methods
    // ============================================================

    private fun findMatchingVersionConfig(
        component: ComponentEntity,
        version: String,
    ): ComponentVersionEntity? {
        val numericVersion = numericVersionFactory.create(version)
        return component.versions.find { versionEntity ->
            try {
                val range = versionRangeFactory.create(versionEntity.versionRange)
                range.containsVersion(numericVersion)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun getJiraComponentVersionRangesByComponent(component: String): Set<JiraComponentVersionRange> {
        val componentEntity =
            componentRepository.findByName(component)
                ?: throw NotFoundException("Component '$component' is not found")
        return buildJiraVersionRangesForComponent(componentEntity)
    }

    private fun buildJiraVersionRangesForComponent(component: ComponentEntity): Set<JiraComponentVersionRange> {
        val ranges = mutableSetOf<JiraComponentVersionRange>()

        // Component-level jira config (no version range — covers all versions)
        val componentJiraConfigs = component.jiraComponentConfigs
        val componentVcsSettings =
            component.vcsSettings.firstOrNull()?.toVCSSettings()
                ?: VCSSettings.create(null, emptyList())
        val componentHotfixEnabled = componentHotfixSupportResolver.isHotFixEnabled(componentVcsSettings)
        val componentDistribution = component.distributions.firstOrNull()?.toDistribution()

        for (jiraConfig in componentJiraConfigs) {
            val jiraComponent = jiraConfig.toJiraComponent(componentHotfixEnabled)
            ranges.add(
                buildJiraComponentVersionRange(
                    component.name,
                    ALL_VERSIONS,
                    jiraComponent,
                    componentDistribution,
                    componentVcsSettings,
                ),
            )
        }

        // Version-specific jira configs
        for (versionEntity in component.versions) {
            val versionJiraConfigs = versionEntity.jiraComponentConfigs
            if (versionJiraConfigs.isEmpty()) continue

            val versionVcsSettings =
                versionEntity.vcsSettings.firstOrNull()?.toVCSSettings()
                    ?: componentVcsSettings
            val versionHotfixEnabled = componentHotfixSupportResolver.isHotFixEnabled(versionVcsSettings)
            val versionDistribution =
                versionEntity.distributions.firstOrNull()?.toDistribution()
                    ?: componentDistribution

            for (jiraConfig in versionJiraConfigs) {
                val jiraComponent = jiraConfig.toJiraComponent(versionHotfixEnabled)
                ranges.add(
                    buildJiraComponentVersionRange(
                        component.name,
                        versionEntity.versionRange,
                        jiraComponent,
                        versionDistribution,
                        versionVcsSettings,
                    ),
                )
            }
        }

        return ranges
    }

    private fun buildJiraComponentVersionRange(
        componentName: String,
        versionRange: String,
        jiraComponent: org.octopusden.octopus.releng.dto.JiraComponent,
        distribution: Distribution?,
        vcsSettings: VCSSettings,
    ): JiraComponentVersionRange =
        jiraComponentVersionRangeFactory.create(
            componentName,
            versionRange,
            jiraComponent,
            distribution,
            vcsSettings,
        )

    private fun getJiraComponentVersionsToRanges(
        version: String,
        versionRanges: Set<JiraComponentVersionRange>,
        strict: Boolean = true,
    ): List<Pair<JiraComponentVersion, JiraComponentVersionRange>> {
        val numericVersion = numericVersionFactory.create(version)
        return versionRanges
            .filter { versionRangeFactory.create(it.versionRange).containsVersion(numericVersion) }
            .mapNotNull { versionRange ->
                val component = versionRange.jiraComponentVersion.component
                val vcsSettings = versionRange.vcsSettings
                jiraComponentVersionFormatter
                    .normalizeVersion(
                        component,
                        version,
                        strict,
                        componentHotfixSupportResolver.isHotFixEnabled(vcsSettings),
                    )?.let { cleanVersion ->
                        JiraComponentVersion(
                            ComponentVersion.create(versionRange.componentName, cleanVersion),
                            versionRange.component,
                            jiraComponentVersionFormatter,
                        ) to versionRange
                    }
            }
    }

    private fun getJiraComponentVersionToRangeByComponentAndVersion(
        component: String,
        version: String,
    ): Pair<JiraComponentVersion, JiraComponentVersionRange> {
        val jiraVersionRanges = getJiraComponentVersionRangesByComponent(component)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges, false)
        return when (results.size) {
            1 -> results.first()
            0 -> throw NotFoundException("Version '$version' for component '$component' is not found")
            else ->
                error(
                    "Found ${results.size} configurations for version '$version' of component '$component'",
                )
        }
    }

    private fun getJiraComponentVersionToRangeByProjectAndVersion(
        projectKey: String,
        version: String,
    ): Pair<JiraComponentVersion, JiraComponentVersionRange> {
        val jiraVersionRanges = getJiraComponentVersionRangesByProject(projectKey)
        val results = getJiraComponentVersionsToRanges(version, jiraVersionRanges)
        return when (results.size) {
            1 -> results.first()
            0 -> throw NotFoundException("Version '$version' for project '$projectKey' is not found")
            else ->
                error(
                    "Found ${results.size} configurations for version '$version' of project '$projectKey'",
                )
        }
    }

    private fun findComponentByArtifactOrNull(artifact: ArtifactDependency): VersionedComponent? {
        val allArtifactIds = componentArtifactIdRepository.findAll()
        for (artifactIdEntity in allArtifactIds) {
            val groupPattern = artifactIdEntity.groupPattern
            val artifactPattern = artifactIdEntity.artifactPattern
            if (MavenArtifactMatcher.groupIdMatches(artifact.group, groupPattern) &&
                MavenArtifactMatcher.artifactIdMatches(artifact.name, artifactPattern)
            ) {
                val componentName = artifactIdEntity.component?.name ?: continue
                return VersionedComponent(componentName, null, artifact.version, "")
            }
        }
        return null
    }

    private fun buildImageToComponentMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (component in componentRepository.findAll()) {
            val allDistributions =
                buildList {
                    addAll(component.distributions)
                    component.versions.forEach { v -> addAll(v.distributions) }
                }
            for (distribution in allDistributions) {
                for (artifact in distribution.artifacts) {
                    if (artifact.artifactType == "DOCKER" && artifact.name != null) {
                        result[artifact.name!!] = component.name
                    }
                }
            }
        }
        return result
    }

    private fun findConfigurationByDockerImage(
        imageName: String,
        imageTag: String,
        componentId: String,
    ): ComponentImage? {
        val versionString =
            try {
                getJiraComponentVersion(componentId, imageTag).version
            } catch (_: NotFoundException) {
                return null
            }
        val component = componentRepository.findByName(componentId) ?: return null
        val matchedVersion = findMatchingVersionConfig(component, versionString)
        val config = applyOverrides(componentId, component.toEscrowModuleConfig(matchedVersion), versionString)
        return config.distribution?.let { dist ->
            if (dist.docker()?.split(',')?.contains("$imageName:$imageTag") == true) {
                ComponentImage(componentId, versionString, Image(imageName, imageTag))
            } else {
                null
            }
        }
    }

    private fun EscrowModule.getBuildSystem(): BuildSystem =
        moduleConfigurations.firstOrNull()?.buildSystem?.toDTO()
            ?: BuildSystem.NOT_SUPPORTED

    private fun EscrowModule.isArchived(): Boolean {
        val moduleConfig = moduleConfigurations.firstOrNull() ?: return false
        return moduleConfig.archived ||
            (
                moduleConfig.componentDisplayName
                    ?.endsWith("ARCHIVED", ignoreCase = true) ?: false
            )
    }

    private fun org.octopusden.octopus.escrow.BuildSystem.toDTO(): BuildSystem =
        when (this) {
            org.octopusden.octopus.escrow.BuildSystem.BS2_0 -> BuildSystem.BS2_0
            org.octopusden.octopus.escrow.BuildSystem.MAVEN -> BuildSystem.MAVEN
            org.octopusden.octopus.escrow.BuildSystem.ECLIPSE_MAVEN -> BuildSystem.ECLIPSE_MAVEN
            org.octopusden.octopus.escrow.BuildSystem.GRADLE -> BuildSystem.GRADLE
            org.octopusden.octopus.escrow.BuildSystem.WHISKEY -> BuildSystem.WHISKEY
            org.octopusden.octopus.escrow.BuildSystem.PROVIDED -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_NOT_SUPPORTED -> BuildSystem.NOT_SUPPORTED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_PROVIDED_MANUALLY -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.GOLANG -> BuildSystem.GOLANG
            org.octopusden.octopus.escrow.BuildSystem.IN_CONTAINER -> BuildSystem.IN_CONTAINER
        }

    private fun applyOverrides(
        componentName: String,
        config: EscrowModuleConfig,
        version: String?,
    ): EscrowModuleConfig {
        val overrides = fieldOverrideRepository.findByComponentName(componentName)
        if (overrides.isEmpty()) return config
        return OverrideApplicator.applyToConfig(config, overrides, version, numericVersionFactory, versionRangeFactory)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseComponentRegistryResolver::class.java)

        /** Must match EscrowConfigurationLoader.ALL_VERSIONS = "(,0),[0,)" */
        @Suppress("VariableNaming")
        private const val ALL_VERSIONS = "(,0),[0,)"
    }
}
