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
import org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.mapper.toEscrowModule
import org.octopusden.octopus.components.registry.server.mapper.toResolvedEscrowModuleConfig
import org.octopusden.octopus.components.registry.server.repository.ComponentRepository
import org.octopusden.octopus.components.registry.server.repository.DependencyMappingRepository
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.util.formatVersion
import org.octopusden.octopus.components.registry.server.util.parseMavenGavEntry
import org.octopusden.octopus.components.registry.server.util.splitCsv
import org.octopusden.octopus.escrow.MavenArtifactMatcher
import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.config.JiraComponentVersionRangeFactory
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.components.registry.api.escrow.Escrow
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

/**
 * Read-side resolver for the v1-v3 API surface, backed by the schema v2
 * `components` + `component_configurations` tables. All resolve calls go through
 * the new `EntityMappers` merge algorithm (schema-spec.md §3.4): base row +
 * matching override rows are combined per request into a single
 * `EscrowModuleConfig` (single-version path) or one config per distinct version
 * range (enumeration path).
 *
 * Synthetic-base handling lives in the mapper (MIG-029 fix); the resolver does
 * not special-case it here.
 */
@Suppress("TooManyFunctions")
@Service("databaseComponentRegistryResolver")
@Transactional(readOnly = true)
class DatabaseComponentRegistryResolver(
    private val componentRepository: ComponentRepository,
    private val dependencyMappingRepository: DependencyMappingRepository,
    private val numericVersionFactory: NumericVersionFactory,
    private val versionRangeFactory: VersionRangeFactory,
    private val versionNames: VersionNames,
) : ComponentRegistryResolver {
    private val jiraComponentVersionFormatter = JiraComponentVersionFormatter(versionNames)
    private val jiraComponentVersionRangeFactory = JiraComponentVersionRangeFactory(versionNames)
    private val componentHotfixSupportResolver = ComponentHotfixSupportResolver()

    override fun updateCache() {
        log.debug("updateCache() called on DatabaseComponentRegistryResolver — no-op")
    }

    override fun getComponents(): MutableCollection<EscrowModule> =
        componentRepository
            .findAll()
            .map { it.toEscrowModule(versionRangeFactory, numericVersionFactory).nullifyEmptyEscrow() }
            .toMutableList()

    override fun getComponentById(id: String): EscrowModule? =
        componentRepository
            .findByComponentKey(id)
            ?.toEscrowModule(versionRangeFactory, numericVersionFactory)
            ?.nullifyEmptyEscrow()

    override fun getResolvedComponentDefinition(
        id: String,
        version: String,
    ): EscrowModuleConfig? {
        val component = componentRepository.findByComponentKey(id) ?: return null
        val config =
            component.toResolvedEscrowModuleConfig(
                version = version,
                versionRangeFactory = versionRangeFactory,
                numericVersionFactory = numericVersionFactory,
            ) ?: return null

        // Null out escrow that has no meaningful data (matches Git-path behaviour
        // for sub-components without an explicit escrow block).
        config.nullifyEmptyEscrow()

        // Mirror the Git resolver: when distribution is present, calculate
        // dist-time substitutions against the jira-normalised version, falling
        // back to the raw version string if normalisation fails.
        if (config.distribution != null) {
            val normalizedVersion =
                try {
                    getJiraComponentVersionToRangeByComponentAndVersion(id, version).first.version
                } catch (_: Exception) {
                    version
                }
            val resolved = EscrowConfigurationLoader.calculateDistribution(config.distribution, normalizedVersion)
            val field = EscrowModuleConfig::class.java.getDeclaredField("distribution")
            field.isAccessible = true
            field.set(config, resolved)
        }
        return config
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
            else -> error("Found ${results.size} configurations for version '$version' of component '$component'")
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
        val buildConfiguration =
            getResolvedComponentDefinition(component, version)?.buildConfiguration
                ?: return emptyList()
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
            .map { it.componentName }
            .toSet()

    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange> {
        val byProject = getAllJiraComponentVersionRanges().filter { it.component.projectKey == projectKey }
        if (byProject.isEmpty()) {
            throw NotFoundException("Project '$projectKey' is not found")
        }
        return byProject.toSet()
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
                    versionRanges.find { it.distribution != null }?.distribution?.securityGroups ?: SecurityGroups(null),
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
        return EscrowConfigurationLoader.calculateDistribution(found.second.distribution, found.first.version)
    }

    override fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRange> =
        componentRepository
            .findAll()
            .flatMap { component -> buildJiraVersionRangesForComponent(component) }
            .toSet()

    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        findComponentByArtifactOrNull(artifact)
            ?: throw NotFoundException("No component found for artifact=$artifact")

    override fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?> {
        log.debug("Find components by artifacts: {}", artifacts)
        return artifacts.associateWith { artifact -> findComponentByArtifactOrNull(artifact) }
    }

    override fun getMavenArtifactParameters(component: String): Map<String, ComponentArtifactConfiguration> {
        val componentEntity =
            componentRepository.findByComponentKey(component)
                ?: throw NotFoundException("Component '$component' is not found")

        // Component-level fallback: the top-level groupId/artifactId rows imported from DSL.
        val artifactIdRows = componentEntity.artifactIds.toList()
        val componentLevelFallback =
            if (artifactIdRows.isEmpty()) {
                null
            } else {
                ComponentArtifactConfiguration(
                    artifactIdRows.first().groupPattern,
                    artifactIdRows.joinToString(",") { it.artifactPattern },
                )
            }

        // Walk the per-range EscrowModule view so that DISTRIBUTION_MAVEN marker
        // overrides are respected: each EscrowModuleConfig already has the correct
        // per-range distribution.GAV() after pickMarkerChildren applied the markers.
        val module = componentEntity.toEscrowModule(versionRangeFactory, numericVersionFactory)

        if (module.moduleConfigurations.isEmpty()) {
            // No configurations at all — return empty map (preserves previous contract)
            return emptyMap()
        }

        return module.moduleConfigurations
            .associate { config ->
                val rangeKey = config.versionRangeString ?: ALL_VERSIONS
                val gavCsv = config.distribution?.GAV()
                val artifact =
                    if (!gavCsv.isNullOrBlank()) {
                        // Per-range GAV present: parse maven entries (skip URL entries).
                        val coords =
                            splitCsv(gavCsv)
                                .filterNot {
                                    it.startsWith("file://") ||
                                        it.startsWith("http://") ||
                                        it.startsWith("https://")
                                }
                                .mapNotNull { parseMavenGavEntry(it) }
                        if (coords.isNotEmpty()) {
                            ComponentArtifactConfiguration(
                                coords.first().groupId,
                                coords.joinToString(",") { it.artifactId },
                            )
                        } else {
                            componentLevelFallback
                        }
                    } else {
                        componentLevelFallback
                    }
                rangeKey to (artifact ?: ComponentArtifactConfiguration("", ""))
            }
            .filterValues { it.groupPattern.isNotEmpty() || it.artifactPattern.isNotEmpty() }
    }

    override fun getDependencyMapping(): Map<String, String> =
        dependencyMappingRepository.findAll().associate { it.alias to it.componentKey }

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
                productType?.let { component.componentKey to it }
            }.toMap()

    override fun findComponentsByDockerImages(images: Set<Image>): Set<ComponentImage> {
        val imageNames = images.map { it.name }.toSet()
        return buildImageToComponentMap()
            .filterKeys(imageNames::contains)
            .mapNotNull { (imgName, compKey) ->
                images.find { it.name == imgName }?.let { requiredImage ->
                    findConfigurationByDockerImage(imgName, requiredImage.tag, compKey)
                }
            }.toSet()
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private fun getJiraComponentVersionRangesByComponent(component: String): Set<JiraComponentVersionRange> {
        val componentEntity =
            componentRepository.findByComponentKey(component)
                ?: throw NotFoundException("Component '$component' is not found")
        return buildJiraVersionRangesForComponent(componentEntity)
    }

    /**
     * Enumerate `JiraComponentVersionRange` entries for one component by walking
     * the EscrowModule view: each `EscrowModuleConfig` with a populated jira
     * project key becomes one range.
     */
    private fun buildJiraVersionRangesForComponent(component: ComponentEntity): Set<JiraComponentVersionRange> {
        val module = component.toEscrowModule(versionRangeFactory, numericVersionFactory)
        val ranges = mutableSetOf<JiraComponentVersionRange>()
        for (config in module.moduleConfigurations) {
            val jiraComponent = config.jiraConfiguration ?: continue
            if (jiraComponent.projectKey.isNullOrBlank()) continue

            val hotfixEnabled = componentHotfixSupportResolver.isHotFixEnabled(config.vcsSettings)
            val jiraWithHotfix =
                org.octopusden.octopus.releng.dto.JiraComponent(
                    jiraComponent.projectKey,
                    jiraComponent.displayName,
                    jiraComponent.componentVersionFormat,
                    jiraComponent.componentInfo,
                    jiraComponent.isTechnical,
                    hotfixEnabled,
                )
            ranges.add(
                jiraComponentVersionRangeFactory.create(
                    component.componentKey,
                    config.versionRangeString ?: ALL_VERSIONS,
                    jiraWithHotfix,
                    config.distribution,
                    config.vcsSettings ?: VCSSettings.create(null, emptyList()),
                ),
            )
        }
        return ranges
    }

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
            else -> error("Found ${results.size} configurations for version '$version' of component '$component'")
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
            else -> error("Found ${results.size} configurations for version '$version' of project '$projectKey'")
        }
    }

    private fun findComponentByArtifactOrNull(artifact: ArtifactDependency): VersionedComponent? {
        val matches =
            componentRepository
                .findAll()
                .flatMap { component ->
                    component.artifactIds.mapNotNull { artifactIdEntity ->
                        toArtifactMatchOrNull(artifactIdEntity, artifact)
                    }
                }
        if (matches.isEmpty()) return null

        // Per-version specificity is not modelled at the component_artifact_ids
        // level under Model A' — those rows belong to the component, not the
        // configuration. Match by pattern specificity alone (mirrors v3 contract
        // since the version-specific tier is empty in production today).
        val resolvedMatch =
            matches.maxWithOrNull(
                compareBy<ArtifactMatch>(
                    { artifactSpecificity(it.artifactPattern, artifact.name) },
                    { it.artifactPattern.length },
                ),
            ) ?: return null

        return VersionedComponent(resolvedMatch.componentName, null, artifact.version, "")
    }

    private fun toArtifactMatchOrNull(
        artifactIdEntity: ComponentArtifactIdEntity,
        artifact: ArtifactDependency,
    ): ArtifactMatch? {
        val groupPattern = artifactIdEntity.groupPattern
        val artifactPattern = artifactIdEntity.artifactPattern
        if (!MavenArtifactMatcher.groupIdMatches(artifact.group, groupPattern) ||
            !MavenArtifactMatcher.artifactIdMatches(artifact.name, artifactPattern)
        ) {
            return null
        }
        return ArtifactMatch(artifactIdEntity.component.componentKey, artifactPattern, false)
    }

    private fun artifactSpecificity(
        artifactPattern: String,
        artifactName: String,
    ): Int =
        when {
            artifactPattern == artifactName -> 3
            artifactPattern == "*" -> 0
            artifactPattern.contains("|") || artifactPattern.contains(",") -> 1
            else -> 2
        }

    private data class ArtifactMatch(
        val componentName: String,
        val artifactPattern: String,
        val versionSpecific: Boolean,
    )

    private fun buildImageToComponentMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (component in componentRepository.findAll()) {
            for (configuration in component.configurations) {
                for (image in configuration.dockerImages) {
                    result[image.imageName] = component.componentKey
                }
            }
        }
        return result
    }

    private fun findConfigurationByDockerImage(
        imageName: String,
        imageTag: String,
        componentKey: String,
    ): ComponentImage? {
        val versionString =
            try {
                getJiraComponentVersion(componentKey, imageTag).version
            } catch (_: NotFoundException) {
                return null
            }
        val component = componentRepository.findByComponentKey(componentKey) ?: return null
        val config =
            component.toResolvedEscrowModuleConfig(
                version = versionString,
                versionRangeFactory = versionRangeFactory,
                numericVersionFactory = numericVersionFactory,
            ) ?: return null
        return config.distribution?.let { dist ->
            if (dist.docker()?.split(',')?.contains("$imageName:$imageTag") == true) {
                ComponentImage(componentKey, versionString, Image(imageName, imageTag))
            } else {
                null
            }
        }
    }

    private fun EscrowModule.getBuildSystem(): BuildSystem =
        moduleConfigurations.firstOrNull()?.buildSystem?.toDTO() ?: BuildSystem.NOT_SUPPORTED

    private fun EscrowModule.isArchived(): Boolean {
        val moduleConfig = moduleConfigurations.firstOrNull() ?: return false
        return moduleConfig.archived ||
            (moduleConfig.componentDisplayName?.endsWith("ARCHIVED", ignoreCase = true) ?: false)
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

    /**
     * Null out `config.escrow` when ALL meaningful escrow fields are absent.
     *
     * `EntityMappers.buildEscrowModuleConfig` always sets `config.escrow` to a
     * non-null anonymous `Escrow` object (because `toEscrowApi()` is
     * unconditional). For components that have no escrow data in the DB
     * (all escrow columns are null), this produces an empty EscrowDTO that
     * the controller serializes as `escrow=EscrowDTO(buildTask=null, ...)`.
     *
     * The Git-path resolver returns `null` for such components, so we match
     * that behaviour here: if none of the meaningful fields carry a real value
     * the escrow object is treated as absent.
     *
     * NOTE: `isReusable()` is intentionally excluded from the emptiness check
     * because the DB column is nullable (null means "not specified") but the
     * `Escrow` interface method returns a primitive `boolean` (defaults to
     * `false`), making `false` indistinguishable from "not specified" at the
     * model level.  All other fields can be checked for meaningful content.
     */
    private fun EscrowModuleConfig.nullifyEmptyEscrow(): EscrowModuleConfig {
        val e: Escrow = escrow ?: return this
        val meaningfulEscrow =
            e.buildTask != null ||
                e.providedDependencies.isNotEmpty() ||
                e.diskSpaceRequirement.isPresent ||
                e.additionalSources.isNotEmpty() ||
                e.generation.isPresent
        if (!meaningfulEscrow) {
            escrow = null
        }
        return this
    }

    /** Apply [nullifyEmptyEscrow] to every config in the module. */
    private fun EscrowModule.nullifyEmptyEscrow(): EscrowModule {
        moduleConfigurations.forEach { it.nullifyEmptyEscrow() }
        return this
    }

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseComponentRegistryResolver::class.java)

        /** Must match EscrowConfigurationLoader.ALL_VERSIONS = "(,0),[0,)" */
        @Suppress("VariableNaming")
        private const val ALL_VERSIONS = "(,0),[0,)"
    }
}
