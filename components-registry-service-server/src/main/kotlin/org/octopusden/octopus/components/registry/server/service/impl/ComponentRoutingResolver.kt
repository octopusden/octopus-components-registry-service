package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.ComponentSourceRegistry
import org.octopusden.octopus.escrow.config.JiraComponentVersionRange
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.dto.ComponentArtifactConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.util.EnumMap

@Service
@Primary
@Suppress("TooManyFunctions")
class ComponentRoutingResolver(
    private val gitResolver: ComponentRegistryResolverImpl,
    @Qualifier("databaseComponentRegistryResolver")
    private val dbResolver: DatabaseComponentRegistryResolver,
    private val sourceRegistry: ComponentSourceRegistry,
) : ComponentRegistryResolver {
    private fun resolverFor(componentName: String): ComponentRegistryResolver =
        if (sourceRegistry.isDbComponent(componentName)) dbResolver else gitResolver

    override fun updateCache() {
        gitResolver.updateCache()
        // DB resolver doesn't need cache update
    }

    // --- Single-component methods: delegate to appropriate resolver ---

    override fun getComponentById(id: String): EscrowModule? = resolverFor(id).getComponentById(id)

    override fun getResolvedComponentDefinition(
        id: String,
        version: String,
    ): EscrowModuleConfig? = resolverFor(id).getResolvedComponentDefinition(id, version)

    override fun getJiraComponentVersion(
        component: String,
        version: String,
    ): JiraComponentVersion = resolverFor(component).getJiraComponentVersion(component, version)

    override fun getJiraComponentVersions(
        component: String,
        versions: List<String>,
    ): Map<String, JiraComponentVersion> = resolverFor(component).getJiraComponentVersions(component, versions)

    override fun getVCSSettings(
        component: String,
        version: String,
    ): VCSSettings = resolverFor(component).getVCSSettings(component, version)

    override fun getBuildTools(
        component: String,
        version: String,
        ignoreRequired: Boolean?,
    ): List<BuildTool> = resolverFor(component).getBuildTools(component, version, ignoreRequired)

    override fun getMavenArtifactParameters(component: String): Map<String, ComponentArtifactConfiguration> =
        resolverFor(component).getMavenArtifactParameters(component)

    // --- Aggregate methods: merge results from both resolvers ---

    override fun getComponents(): MutableCollection<EscrowModule> {
        val gitComponents = gitResolver.getComponents()
        val dbComponents = dbResolver.getComponents()
        val dbNames = sourceRegistry.getDbComponentNames()

        val filteredGit = gitComponents.filter { !dbNames.contains(it.moduleName) }

        val result = mutableListOf<EscrowModule>()
        result.addAll(filteredGit)
        result.addAll(dbComponents)
        return result
    }

    override fun getAllJiraComponentVersionRanges(): Set<JiraComponentVersionRange> {
        val gitRanges = gitResolver.getAllJiraComponentVersionRanges()
        val dbRanges = dbResolver.getAllJiraComponentVersionRanges()
        val dbNames = sourceRegistry.getDbComponentNames()

        val filteredGit = gitRanges.filter { !dbNames.contains(it.componentName) }
        return filteredGit.toSet() + dbRanges
    }

    // --- Jira project methods: try both resolvers ---

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getJiraComponentByProjectAndVersion(
        projectKey: String,
        version: String,
    ): JiraComponentVersion =
        try {
            dbResolver.getJiraComponentByProjectAndVersion(projectKey, version)
        } catch (e: Exception) {
            gitResolver.getJiraComponentByProjectAndVersion(projectKey, version)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getJiraComponentsByProject(projectKey: String): Set<String> {
        val gitResults =
            try {
                gitResolver.getJiraComponentsByProject(projectKey)
            } catch (e: Exception) {
                emptySet()
            }
        val dbResults =
            try {
                dbResolver.getJiraComponentsByProject(projectKey)
            } catch (e: Exception) {
                emptySet()
            }
        return gitResults + dbResults
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange> {
        val gitResults =
            try {
                gitResolver.getJiraComponentVersionRangesByProject(projectKey)
            } catch (e: Exception) {
                emptySet()
            }
        val dbResults =
            try {
                dbResolver.getJiraComponentVersionRangesByProject(projectKey)
            } catch (e: Exception) {
                emptySet()
            }
        val dbNames = sourceRegistry.getDbComponentNames()
        val filteredGit = gitResults.filter { !dbNames.contains(it.componentName) }
        return filteredGit.toSet() + dbResults
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution> {
        val gitResults =
            try {
                gitResolver.getComponentsDistributionByJiraProject(projectKey)
            } catch (e: Exception) {
                emptyMap()
            }
        val dbResults =
            try {
                dbResolver.getComponentsDistributionByJiraProject(projectKey)
            } catch (e: Exception) {
                emptyMap()
            }
        val dbNames = sourceRegistry.getDbComponentNames()
        val filteredGit = gitResults.filter { !dbNames.contains(it.key) }
        return filteredGit + dbResults
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getVCSSettingForProject(
        projectKey: String,
        version: String,
    ): VCSSettings =
        try {
            dbResolver.getVCSSettingForProject(projectKey, version)
        } catch (e: Exception) {
            gitResolver.getVCSSettingForProject(projectKey, version)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getDistributionForProject(
        projectKey: String,
        version: String,
    ): Distribution =
        try {
            dbResolver.getDistributionForProject(projectKey, version)
        } catch (e: Exception) {
            gitResolver.getDistributionForProject(projectKey, version)
        }

    // --- Artifact resolution: try both ---

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun findComponentByArtifact(artifact: ArtifactDependency): VersionedComponent =
        try {
            dbResolver.findComponentByArtifact(artifact)
        } catch (e: Exception) {
            gitResolver.findComponentByArtifact(artifact)
        }

    override fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?> {
        val gitResults = gitResolver.findComponentsByArtifact(artifacts)
        val dbResults = dbResolver.findComponentsByArtifact(artifacts)
        val dbNames = sourceRegistry.getDbComponentNames()

        return artifacts.associateWith { artifact ->
            val dbResult = dbResults[artifact]
            val gitResult = gitResults[artifact]
            if (dbResult != null && dbNames.contains(dbResult.id)) {
                dbResult
            } else {
                gitResult ?: dbResult
            }
        }
    }

    override fun getDependencyMapping(): Map<String, String> {
        val gitMapping = gitResolver.getDependencyMapping()
        val dbMapping = dbResolver.getDependencyMapping()
        return gitMapping + dbMapping // DB overwrites git for same keys
    }

    override fun getComponentsCountByBuildSystem(): EnumMap<BuildSystem, Int> {
        val gitCounts = gitResolver.getComponentsCountByBuildSystem()
        val dbCounts = dbResolver.getComponentsCountByBuildSystem()

        val result = EnumMap<BuildSystem, Int>(BuildSystem::class.java)
        BuildSystem.values().forEach { bs ->
            result[bs] = (gitCounts[bs] ?: 0) + (dbCounts[bs] ?: 0)
        }
        return result
    }

    override fun getComponentProductMapping(): Map<String, ProductTypes> {
        val gitMapping = gitResolver.getComponentProductMapping()
        val dbMapping = dbResolver.getComponentProductMapping()
        val dbNames = sourceRegistry.getDbComponentNames()
        val filteredGit = gitMapping.filter { !dbNames.contains(it.key) }
        return filteredGit + dbMapping
    }

    override fun findComponentsByDockerImages(images: Set<Image>): Set<ComponentImage> {
        val gitResults = gitResolver.findComponentsByDockerImages(images)
        val dbResults = dbResolver.findComponentsByDockerImages(images)
        return gitResults + dbResults
    }
}
