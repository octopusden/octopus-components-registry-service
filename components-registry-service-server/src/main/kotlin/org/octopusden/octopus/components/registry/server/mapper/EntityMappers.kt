@file:Suppress("TooManyFunctions")

package org.octopusden.octopus.components.registry.server.mapper

import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.server.entity.BuildConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentEntity
import org.octopusden.octopus.components.registry.server.entity.ComponentVersionEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionArtifactEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionEntity
import org.octopusden.octopus.components.registry.server.entity.DistributionSecurityGroupEntity
import org.octopusden.octopus.components.registry.server.entity.EscrowConfigurationEntity
import org.octopusden.octopus.components.registry.server.entity.JiraComponentConfigEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntity
import org.octopusden.octopus.components.registry.server.entity.VcsSettingsEntryEntity
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.escrow.model.VersionControlSystemRoot
import org.octopusden.octopus.releng.dto.JiraComponent

/** Must match EscrowConfigurationLoader.ALL_VERSIONS = "(,0),[0,)" */
private const val ALL_VERSIONS = "(,0),[0,)"

// ============================================================
// Entity → Domain Model mappers (DB → existing Groovy/Java DTOs)
// ============================================================

/**
 * Convert ComponentEntity to EscrowModule (used by ComponentRegistryResolver interface).
 * Each component version config becomes a separate EscrowModuleConfig.
 */
fun ComponentEntity.toEscrowModule(): EscrowModule {
    val module = EscrowModule()
    module.moduleName = this.name

    // Component-level config (default, no version range)
    val defaultConfig = this.toEscrowModuleConfig(null)
    module.moduleConfigurations.add(defaultConfig)

    // Version-specific configs
    for (version in this.versions) {
        val versionConfig = this.toEscrowModuleConfig(version)
        module.moduleConfigurations.add(versionConfig)
    }

    return module
}

/**
 * Convert ComponentEntity to EscrowModuleConfig.
 * If componentVersion is provided, version-specific overrides are applied.
 */
@Suppress("CyclomaticComplexMethod")
fun ComponentEntity.toEscrowModuleConfig(componentVersion: ComponentVersionEntity?): EscrowModuleConfig {
    val config = EscrowModuleConfig()

    // Version range from component version (if present), or ALL_VERSIONS for default config
    if (componentVersion != null) {
        setEscrowModuleConfigField(config, "versionRange", componentVersion.versionRange)
    } else {
        setEscrowModuleConfigField(config, "versionRange", ALL_VERSIONS)
    }

    // Build configuration
    val buildConfig =
        if (componentVersion != null) {
            componentVersion.buildConfigurations.firstOrNull() ?: this.buildConfigurations.firstOrNull()
        } else {
            this.buildConfigurations.firstOrNull()
        }
    if (buildConfig != null) {
        setEscrowModuleConfigField(config, "buildSystem", buildConfig.buildSystem?.let { safeParseBuildSystem(it) })
        setEscrowModuleConfigField(config, "buildFilePath", buildConfig.buildFilePath)
        setEscrowModuleConfigField(config, "deprecated", buildConfig.deprecated)

        // Build parameters from metadata
        val buildParams = buildConfig.toBuildParameters()
        if (buildParams != null) {
            setEscrowModuleConfigField(config, "buildConfiguration", buildParams)
        }
    }

    // Escrow configuration
    val escrowConfig =
        if (componentVersion != null) {
            componentVersion.escrowConfigurations.firstOrNull() ?: this.escrowConfigurations.firstOrNull()
        } else {
            this.escrowConfigurations.firstOrNull()
        }
    if (escrowConfig != null) {
        config.escrow = escrowConfig.toEscrowApi()
    }

    // VCS Settings
    val vcsSettingsEntity =
        if (componentVersion != null) {
            componentVersion.vcsSettings.firstOrNull() ?: this.vcsSettings.firstOrNull()
        } else {
            this.vcsSettings.firstOrNull()
        }
    if (vcsSettingsEntity != null) {
        setEscrowModuleConfigField(config, "vcsSettings", vcsSettingsEntity.toVCSSettings())
    }

    // Distribution
    val distributionEntity =
        if (componentVersion != null) {
            componentVersion.distributions.firstOrNull() ?: this.distributions.firstOrNull()
        } else {
            this.distributions.firstOrNull()
        }
    if (distributionEntity != null) {
        setEscrowModuleConfigField(config, "distribution", distributionEntity.toDistribution())
    }

    // Jira configuration
    val jiraConfig =
        if (componentVersion != null) {
            componentVersion.jiraComponentConfigs.firstOrNull() ?: this.jiraComponentConfigs.firstOrNull()
        } else {
            this.jiraComponentConfigs.firstOrNull()
        }
    if (jiraConfig != null) {
        setEscrowModuleConfigField(config, "jiraConfiguration", jiraConfig.toJiraComponent())
    }

    // Tier 1 fields
    setEscrowModuleConfigField(config, "componentDisplayName", this.displayName)
    setEscrowModuleConfigField(config, "componentOwner", this.componentOwner)
    setEscrowModuleConfigField(config, "system", this.system.joinToString(","))
    setEscrowModuleConfigField(config, "clientCode", this.clientCode)
    setEscrowModuleConfigField(config, "solution", this.solution)
    setEscrowModuleConfigField(config, "parentComponent", this.parentComponent?.name)
    setEscrowModuleConfigField(config, "archived", this.archived)
    config.productType = this.productType?.let { safeParseProductType(it) }

    // Tier 3 metadata fields
    setEscrowModuleConfigField(config, "releaseManager", this.metadata["releaseManager"] as? String)
    setEscrowModuleConfigField(config, "securityChampion", this.metadata["securityChampion"] as? String)
    setEscrowModuleConfigField(config, "copyright", this.metadata["copyright"] as? String)
    setEscrowModuleConfigField(config, "releasesInDefaultBranch", this.metadata["releasesInDefaultBranch"] as? Boolean)

    @Suppress("UNCHECKED_CAST")
    val labels = this.metadata["labels"] as? Collection<String>
    if (labels != null) {
        setEscrowModuleConfigField(config, "labels", labels.toSet())
    }

    val docUrl = this.metadata["docUrl"] as? String
    val docBranch = this.metadata["docBranch"] as? String
    if (docUrl != null) {
        config.doc =
            org.octopusden.octopus.escrow.model
                .Doc(docUrl, docBranch)
    }

    // Artifact patterns: version-specific first, then component-level
    val artifactId =
        if (componentVersion != null) {
            componentVersion.artifactIds.firstOrNull() ?: this.artifactIds.firstOrNull()
        } else {
            this.artifactIds.firstOrNull()
        }
    if (artifactId != null) {
        setEscrowModuleConfigField(config, "groupIdPattern", artifactId.groupPattern)
        setEscrowModuleConfigField(config, "artifactIdPattern", artifactId.artifactPattern)
    }

    return config
}

// ============================================================
// Sub-entity → Domain Model mappers
// ============================================================

fun BuildConfigurationEntity.toBuildParameters(): BuildParameters? {
    val javaVer = this.javaVersion ?: this.metadata["javaVersion"] as? String
    val mavenVer = this.metadata["mavenVersion"] as? String
    val gradleVer = this.metadata["gradleVersion"] as? String
    val requiredProject = this.metadata["requiredProject"] as? Boolean ?: false
    val projectVersion = this.metadata["projectVersion"] as? String
    val systemProperties = this.metadata["systemProperties"] as? String
    val buildTasks = this.metadata["buildTasks"] as? String

    @Suppress("UNCHECKED_CAST")
    val toolsList =
        (this.metadata["tools"] as? List<Map<String, String?>>)?.map { toolMap ->
            org.octopusden.octopus.escrow.model.Tool(
                toolMap["name"],
                toolMap["escrowEnvironmentVariable"],
                toolMap["sourceLocation"],
                toolMap["targetLocation"],
                toolMap["installScript"],
            )
        } ?: emptyList()

    return BuildParameters.create(
        javaVer,
        mavenVer,
        gradleVer,
        requiredProject,
        projectVersion,
        systemProperties,
        buildTasks,
        toolsList,
        emptyList(),
    )
}

fun VcsSettingsEntity.toVCSSettings(): VCSSettings {
    val roots =
        this.entries.map { entry ->
            VersionControlSystemRoot.create(
                entry.name ?: "main",
                org.octopusden.octopus.escrow.RepositoryType
                    .valueOf(entry.repositoryType),
                entry.vcsPath,
                entry.tag,
                entry.branch,
                entry.hotfixBranch,
            )
        }
    return VCSSettings.create(this.externalRegistry, roots)
}

fun DistributionEntity.toDistribution(): Distribution {
    val gavArtifact = this.artifacts.firstOrNull { it.artifactType == "GAV" }
    val debArtifact = this.artifacts.firstOrNull { it.artifactType == "DEB" }
    val rpmArtifact = this.artifacts.firstOrNull { it.artifactType == "RPM" }
    val dockerArtifact = this.artifacts.firstOrNull { it.artifactType == "DOCKER" }

    val gavStr =
        gavArtifact?.let {
            if (it.name != null) {
                // Multi-GAV stored as raw string
                it.name
            } else {
                buildString {
                    append("${it.groupPattern}:${it.artifactPattern}")
                    it.extension?.let { ext -> append(":$ext") }
                    it.classifier?.let { cls -> append(":$cls") }
                }
            }
        }
    val debStr = debArtifact?.name
    val rpmStr = rpmArtifact?.name
    val dockerStr =
        dockerArtifact?.let {
            if (it.tag != null) "${it.name}:${it.tag}" else it.name
        }

    val secGroups =
        this.securityGroups
            .filter { it.groupType == "read" }
            .joinToString(",") { it.groupName }

    val securityGroups = if (secGroups.isNotEmpty()) SecurityGroups(secGroups) else null

    return Distribution(this.explicit, this.external, gavStr, debStr, rpmStr, dockerStr, securityGroups)
}

fun JiraComponentConfigEntity.toJiraComponent(hotfixEnabled: Boolean = false): JiraComponent {
    @Suppress("UNCHECKED_CAST")
    val versionFormatMap = this.componentVersionFormat ?: emptyMap()

    val majorVersionFormat = versionFormatMap["majorVersionFormat"] as? String ?: "\$major"
    val releaseVersionFormat = versionFormatMap["releaseVersionFormat"] as? String ?: "\$major.\$minor"
    // ModelConfigPostProcessor.resolveJiraConfiguration() defaults buildVersionFormat → releaseVersionFormat
    val buildVersionFormat = versionFormatMap["buildVersionFormat"] as? String ?: releaseVersionFormat
    // ComponentVersionFormat.create() defaults lineVersionFormat to majorVersionFormat when null
    val lineVersionFormat = versionFormatMap["lineVersionFormat"] as? String ?: majorVersionFormat
    val hotfixVersionFormat = versionFormatMap["hotfixVersionFormat"] as? String ?: ""

    val componentVersionFormat =
        org.octopusden.releng.versions.ComponentVersionFormat.create(
            majorVersionFormat,
            releaseVersionFormat,
            buildVersionFormat,
            lineVersionFormat,
            hotfixVersionFormat,
        )

    val versionPrefix = this.metadata["versionPrefix"] as? String ?: ""
    val versionFormat = this.metadata["versionFormat"] as? String ?: ""
    val componentInfo =
        org.octopusden.octopus.releng.dto
            .ComponentInfo(versionPrefix, versionFormat)

    return JiraComponent(
        this.projectKey ?: "",
        this.displayName,
        componentVersionFormat,
        componentInfo,
        this.technical,
        hotfixEnabled,
    )
}

fun EscrowConfigurationEntity.toEscrowApi(): org.octopusden.octopus.components.registry.api.escrow.Escrow {
    val entity = this
    return object : org.octopusden.octopus.components.registry.api.escrow.Escrow {
        override fun getGradle() = null

        override fun getBuildTask() = entity.buildTask

        override fun getProvidedDependencies(): Collection<String> =
            entity.providedDependencies
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: emptyList()

        override fun getDiskSpaceRequirement() = java.util.Optional.ofNullable(entity.diskSpace?.toLongOrNull())

        override fun getAdditionalSources(): Collection<String> = emptyList()

        override fun isReusable() = entity.reusable ?: false

        override fun getGeneration() =
            java.util.Optional.ofNullable(
                entity.generation?.let {
                    try {
                        org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
                            .valueOf(it)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                },
            )
    }
}

// ============================================================
// Utility functions
// ============================================================

private fun safeParseBuildSystem(value: String): BuildSystem? =
    try {
        BuildSystem.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

private fun safeParseProductType(value: String): ProductTypes? =
    try {
        ProductTypes.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Helper to set a private field on EscrowModuleConfig via reflection.
 */
private fun setEscrowModuleConfigField(
    config: EscrowModuleConfig,
    name: String,
    value: Any?,
) {
    try {
        val field = EscrowModuleConfig::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(config, value)
    } catch (_: NoSuchFieldException) {
        // Ignore unknown fields
    }
}

// ============================================================
// Domain Model → Entity mappers (for import/migration)
// ============================================================

/**
 * Convert EscrowModule to ComponentEntity (for import from Git DSL).
 *
 * EscrowConfigurationLoader creates moduleConfigurations in two modes:
 * 1. Single ALL_VERSIONS config — when no version-specific blocks exist
 * 2. Only version-specific configs — when version-specific blocks exist (NO ALL_VERSIONS)
 * We detect this by checking if the first config's versionRangeString == ALL_VERSIONS.
 */
@Suppress("CyclomaticComplexMethod")
fun EscrowModule.toComponentEntity(): ComponentEntity {
    val allConfigs = this.moduleConfigurations
    if (allConfigs.isEmpty()) return ComponentEntity(name = this.moduleName)

    val firstConfig = allConfigs.first()
    val hasDefaultConfig = firstConfig.versionRangeString == ALL_VERSIONS

    // Use first config for component-level scalar properties (same across all versions)
    val entity =
        ComponentEntity(
            name = this.moduleName,
            componentOwner = firstConfig.componentOwner,
            displayName = firstConfig.componentDisplayName,
            productType = firstConfig.productType?.name,
            system = firstConfig.systemSet.toTypedArray(),
            clientCode = firstConfig.clientCode,
            archived = firstConfig.archived,
            solution = firstConfig.solution,
        )

    // Tier 3 metadata
    entity.metadata =
        mutableMapOf<String, Any?>().apply {
            firstConfig.releaseManager?.let { put("releaseManager", it) }
            firstConfig.securityChampion?.let { put("securityChampion", it) }
            firstConfig.copyright?.let { put("copyright", it) }
            firstConfig.releasesInDefaultBranch?.let { put("releasesInDefaultBranch", it) }
            firstConfig.labels?.let { put("labels", it.toList()) }
            firstConfig.doc?.let {
                put("docUrl", it.component())
                put("docBranch", it.majorVersion())
            }
        }

    // Build config + escrow: always stored at component level (shared across versions)
    addComponentLevelBuildAndEscrow(entity, firstConfig)

    // Distribution, VCS, artifact IDs: always at component level (needed for GET /components/{name})
    addComponentLevelDistributionVcsArtifacts(entity, firstConfig)

    // Jira: only at component level for ALL_VERSIONS default (avoids overlapping ranges)
    if (hasDefaultConfig && firstConfig.jiraConfiguration != null) {
        entity.jiraComponentConfigs.add(firstConfig.jiraConfiguration.toJiraConfigEntity(entity, null))
    }

    // Version-specific configs: skip first only if it's the ALL_VERSIONS default
    val startIdx = if (hasDefaultConfig) 1 else 0
    for (i in startIdx until allConfigs.size) {
        val versionConfig = allConfigs[i]
        val versionEntity =
            ComponentVersionEntity(
                component = entity,
                versionRange = versionConfig.versionRangeString ?: ALL_VERSIONS,
            )

        if (versionConfig.buildSystem != null || versionConfig.buildFilePath != null) {
            versionEntity.buildConfigurations.add(
                versionConfig.toBuildConfigurationEntity(null, versionEntity),
            )
        }
        if (versionConfig.vcsSettings != null) {
            versionEntity.vcsSettings.add(versionConfig.vcsSettings.toVcsSettingsEntity(null, versionEntity))
        }
        if (versionConfig.distribution != null) {
            versionEntity.distributions.add(versionConfig.distribution.toDistributionEntity(null, versionEntity))
        }
        if (versionConfig.jiraConfiguration != null) {
            versionEntity.jiraComponentConfigs.add(versionConfig.jiraConfiguration.toJiraConfigEntity(null, versionEntity))
        }
        if (versionConfig.escrow != null) {
            versionEntity.escrowConfigurations.add(versionConfig.escrow.toEscrowConfigEntity(null, versionEntity))
        }
        if (versionConfig.groupIdPattern != null || versionConfig.artifactIdPattern != null) {
            versionEntity.artifactIds.add(
                org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity(
                    componentVersion = versionEntity,
                    groupPattern = versionConfig.groupIdPattern ?: "",
                    artifactPattern = versionConfig.artifactIdPattern ?: "",
                ),
            )
        }

        entity.versions.add(versionEntity)
    }

    return entity
}

/** Store build config + escrow at the component level (shared across all versions). */
private fun addComponentLevelBuildAndEscrow(
    entity: ComponentEntity,
    config: EscrowModuleConfig,
) {
    if (config.buildSystem != null || config.buildFilePath != null) {
        entity.buildConfigurations.add(config.toBuildConfigurationEntity(entity, null))
    }

    if (config.escrow != null) {
        entity.escrowConfigurations.add(config.escrow.toEscrowConfigEntity(entity, null))
    }
}

/** Store distribution, VCS, artifact IDs at the component level (always, needed for GET /components/{name}). */
private fun addComponentLevelDistributionVcsArtifacts(
    entity: ComponentEntity,
    config: EscrowModuleConfig,
) {
    if (config.vcsSettings != null) {
        entity.vcsSettings.add(config.vcsSettings.toVcsSettingsEntity(entity, null))
    }

    if (config.distribution != null) {
        entity.distributions.add(config.distribution.toDistributionEntity(entity, null))
    }

    if (config.groupIdPattern != null || config.artifactIdPattern != null) {
        entity.artifactIds.add(
            org.octopusden.octopus.components.registry.server.entity.ComponentArtifactIdEntity(
                component = entity,
                groupPattern = config.groupIdPattern ?: "",
                artifactPattern = config.artifactIdPattern ?: "",
            ),
        )
    }
}

// ============================================================
// Domain → Entity sub-mappers (for import)
// ============================================================

private fun EscrowModuleConfig.toBuildConfigurationEntity(
    component: ComponentEntity?,
    version: ComponentVersionEntity?,
): BuildConfigurationEntity {
    val config =
        BuildConfigurationEntity(
            component = component,
            componentVersion = version,
            buildSystem = this.buildSystem?.name,
            buildFilePath = this.buildFilePath,
            deprecated = this.isDeprecated,
        )
    if (this.buildConfiguration != null) {
        config.javaVersion = this.buildConfiguration.javaVersion
        config.metadata =
            mutableMapOf<String, Any?>().apply {
                this@toBuildConfigurationEntity.buildConfiguration.mavenVersion?.let { put("mavenVersion", it) }
                this@toBuildConfigurationEntity.buildConfiguration.gradleVersion?.let { put("gradleVersion", it) }
                put("requiredProject", this@toBuildConfigurationEntity.buildConfiguration.requiredProject)
                this@toBuildConfigurationEntity.buildConfiguration.projectVersion?.let { put("projectVersion", it) }
                this@toBuildConfigurationEntity.buildConfiguration.systemProperties?.let { put("systemProperties", it) }
                this@toBuildConfigurationEntity.buildConfiguration.buildTasks?.let { put("buildTasks", it) }
                val tools = this@toBuildConfigurationEntity.buildConfiguration.tools
                if (tools != null && tools.isNotEmpty()) {
                    put(
                        "tools",
                        tools.map { tool ->
                            mapOf(
                                "name" to tool.name,
                                "escrowEnvironmentVariable" to tool.escrowEnvironmentVariable,
                                "sourceLocation" to tool.sourceLocation,
                                "targetLocation" to tool.targetLocation,
                                "installScript" to tool.installScript,
                            )
                        },
                    )
                }
            }
    }
    return config
}

private fun org.octopusden.octopus.components.registry.api.escrow.Escrow.toEscrowConfigEntity(
    component: ComponentEntity?,
    version: ComponentVersionEntity?,
): EscrowConfigurationEntity =
    EscrowConfigurationEntity(
        component = component,
        componentVersion = version,
        buildTask = this.buildTask,
        providedDependencies = this.providedDependencies.joinToString(","),
        reusable = this.isReusable,
        generation = this.generation.orElse(null)?.name,
        diskSpace = this.diskSpaceRequirement.orElse(null)?.toString(),
    )

private fun VCSSettings.toVcsSettingsEntity(
    component: ComponentEntity?,
    version: ComponentVersionEntity?,
): VcsSettingsEntity {
    val entity =
        VcsSettingsEntity(
            component = component,
            componentVersion = version,
            vcsType = if (this.versionControlSystemRoots.size > 1) "MULTIPLY" else "SINGLE",
            externalRegistry = this.externalRegistry,
        )
    for (root in this.versionControlSystemRoots) {
        entity.entries.add(
            VcsSettingsEntryEntity(
                vcsSettings = entity,
                name = root.name,
                vcsPath = root.vcsPath,
                repositoryType = root.repositoryType.name,
                tag = root.tag,
                branch = root.branch,
                hotfixBranch = root.hotfixBranch,
            ),
        )
    }
    return entity
}

private fun Distribution.toDistributionEntity(
    component: ComponentEntity?,
    version: ComponentVersionEntity?,
): DistributionEntity {
    val entity =
        DistributionEntity(
            component = component,
            componentVersion = version,
            explicit = this.explicit(),
            external = this.external(),
        )

    this.GAV()?.let { gav ->
        if (gav.contains(",")) {
            // Multi-GAV: store raw string as-is (can't split into group:artifact reliably)
            entity.artifacts.add(
                DistributionArtifactEntity(
                    distribution = entity,
                    artifactType = "GAV",
                    name = gav,
                ),
            )
        } else {
            val parts = gav.split(":")
            entity.artifacts.add(
                DistributionArtifactEntity(
                    distribution = entity,
                    artifactType = "GAV",
                    groupPattern = parts.getOrNull(0),
                    artifactPattern = parts.getOrNull(1),
                    extension = parts.getOrNull(2),
                    classifier = parts.getOrNull(3),
                ),
            )
        }
    }
    this.DEB()?.let { deb ->
        entity.artifacts.add(
            DistributionArtifactEntity(distribution = entity, artifactType = "DEB", name = deb),
        )
    }
    this.RPM()?.let { rpm ->
        entity.artifacts.add(
            DistributionArtifactEntity(distribution = entity, artifactType = "RPM", name = rpm),
        )
    }
    this.docker()?.let { docker ->
        val dockerParts = docker.split(":")
        entity.artifacts.add(
            DistributionArtifactEntity(
                distribution = entity,
                artifactType = "DOCKER",
                name = dockerParts.getOrNull(0),
                tag = dockerParts.getOrNull(1),
            ),
        )
    }

    this.securityGroups?.read?.split(",")?.filter { it.isNotBlank() }?.forEach { group ->
        entity.securityGroups.add(
            DistributionSecurityGroupEntity(distribution = entity, groupType = "read", groupName = group.trim()),
        )
    }

    return entity
}

private fun JiraComponent.toJiraConfigEntity(
    component: ComponentEntity?,
    version: ComponentVersionEntity?,
): JiraComponentConfigEntity =
    JiraComponentConfigEntity(
        component = component,
        componentVersion = version,
        projectKey = this.projectKey,
        displayName = this.displayName,
        componentVersionFormat =
            mapOf(
                "majorVersionFormat" to this.componentVersionFormat.majorVersionFormat,
                "releaseVersionFormat" to this.componentVersionFormat.releaseVersionFormat,
                "buildVersionFormat" to this.componentVersionFormat.buildVersionFormat,
                "lineVersionFormat" to this.componentVersionFormat.lineVersionFormat,
                "hotfixVersionFormat" to this.componentVersionFormat.hotfixVersionFormat,
            ),
        technical = this.isTechnical,
        metadata =
            mutableMapOf<String, Any?>().apply {
                this@toJiraConfigEntity.componentInfo?.let {
                    put("versionPrefix", it.versionPrefix)
                    put("versionFormat", it.versionFormat)
                }
            },
    )
