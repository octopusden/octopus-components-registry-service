package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
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
        if (sourceRegistry.getSource(componentName) == "db") dbResolver else gitResolver

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
        } catch (e: NotFoundException) {
            // db has no match — try git, but reject stale data for DB-sourced components
            val gitResult = gitResolver.getJiraComponentByProjectAndVersion(projectKey, version)
            if (sourceRegistry.getDbComponentNames().contains(gitResult.componentVersion.componentName)) {
                throw NotFoundException(
                    "Component '${gitResult.componentVersion.componentName}' is db-sourced; " +
                        "version '$version' for project '$projectKey' is not in DB",
                )
            }
            gitResult
        } catch (e: Exception) {
            // transient — fall back to git (fault tolerance preserved)
            gitResolver.getJiraComponentByProjectAndVersion(projectKey, version)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getJiraComponentsByProject(projectKey: String): Set<String> {
        var gitNotFound = false
        var dbNotFound = false
        val gitResults =
            try {
                gitResolver.getJiraComponentsByProject(projectKey)
            } catch (e: NotFoundException) {
                gitNotFound = true
                emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        val dbResults =
            try {
                dbResolver.getJiraComponentsByProject(projectKey)
            } catch (e: NotFoundException) {
                dbNotFound = true
                emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        // MIG-049: when BOTH resolvers say the project is unknown, propagate
        // NotFoundException so the controller exception handler renders 404.
        // Other exception types from either resolver (e.g. transient DB
        // failure) are still swallowed so a partial outage doesn't degrade
        // the union-merge response.
        if (gitNotFound && dbNotFound) {
            throw NotFoundException("Project '$projectKey' is not found")
        }
        return gitResults + dbResults
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getJiraComponentVersionRangesByProject(projectKey: String): Set<JiraComponentVersionRange> {
        var gitNotFound = false
        var dbNotFound = false
        val gitResults =
            try {
                gitResolver.getJiraComponentVersionRangesByProject(projectKey)
            } catch (e: NotFoundException) {
                gitNotFound = true
                emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        val dbResults =
            try {
                dbResolver.getJiraComponentVersionRangesByProject(projectKey)
            } catch (e: NotFoundException) {
                dbNotFound = true
                emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        // MIG-049: see getJiraComponentsByProject for the same pattern.
        if (gitNotFound && dbNotFound) {
            throw NotFoundException("Project '$projectKey' is not found")
        }
        val dbNames = sourceRegistry.getDbComponentNames()
        val filteredGit = gitResults.filter { !dbNames.contains(it.componentName) }
        return filteredGit.toSet() + dbResults
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun getComponentsDistributionByJiraProject(projectKey: String): Map<String, Distribution> {
        var gitNotFound = false
        var dbNotFound = false
        val gitResults =
            try {
                gitResolver.getComponentsDistributionByJiraProject(projectKey)
            } catch (e: NotFoundException) {
                gitNotFound = true
                emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        val dbResults =
            try {
                dbResolver.getComponentsDistributionByJiraProject(projectKey)
            } catch (e: NotFoundException) {
                dbNotFound = true
                emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        // MIG-049: see getJiraComponentsByProject for the same pattern.
        if (gitNotFound && dbNotFound) {
            throw NotFoundException("Project '$projectKey' is not found")
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
        } catch (e: NotFoundException) {
            // db has no match — try git, but reject stale data for DB-sourced components
            val gitResult = gitResolver.findComponentByArtifact(artifact)
            if (sourceRegistry.getDbComponentNames().contains(gitResult.id)) {
                throw NotFoundException(
                    "Component '${gitResult.id}' is db-sourced; artifact " +
                        "'${artifact.group}:${artifact.name}:${artifact.version}' is not in DB",
                )
            }
            gitResult
        } catch (e: Exception) {
            // transient — fall back to git (fault tolerance preserved)
            gitResolver.findComponentByArtifact(artifact)
        }

    override fun findComponentsByArtifact(artifacts: Set<ArtifactDependency>): Map<ArtifactDependency, VersionedComponent?> {
        val gitResults = gitResolver.findComponentsByArtifact(artifacts)
        val dbResults = dbResolver.findComponentsByArtifact(artifacts)
        val dbNames = sourceRegistry.getDbComponentNames()

        return artifacts.associateWith { artifact ->
            val dbResult = dbResults[artifact]
            val gitResult = gitResults[artifact]
            when {
                // 1. DB has a match → authoritative (regardless of whether the component is yet
                //    registered in dbNames — partial migrations / pre-source-row inserts must not
                //    drop a legitimate DB hit because git stale-matched a DIFFERENT db-sourced
                //    component for the same artifact)
                dbResult != null -> dbResult
                // 2. DB has no match, git stale-matches a db-sourced component → reject
                gitResult != null && dbNames.contains(gitResult.id) -> null
                // 3. Legitimate git-only fallback
                else -> gitResult
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
        val dbResults = dbResolver.findComponentsByDockerImages(images)
        val gitResults = gitResolver.findComponentsByDockerImages(images)
        val dbNames = sourceRegistry.getDbComponentNames()
        // Drop git results whose component is DB-sourced: for migrated components the DB
        // is authoritative, and a git stale match would mask a true absence with legacy data.
        val gitFiltered = gitResults.filterNot { dbNames.contains(it.component) }.toSet()
        return gitFiltered + dbResults
    }
}
