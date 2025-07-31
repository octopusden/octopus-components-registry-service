package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.escrow.Escrow
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.BuildParametersDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentArtifactConfigurationDTO
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersion
import org.octopusden.octopus.components.registry.core.dto.DetailedComponentVersions
import org.octopusden.octopus.components.registry.core.dto.EscrowDTO
import org.octopusden.octopus.components.registry.core.dto.JiraComponentVersionDTO
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.dto.ToolDTO
import org.octopusden.octopus.components.registry.core.dto.VCSSettingsDTO
import org.octopusden.octopus.components.registry.core.dto.VersionControlSystemRootDTO
import org.octopusden.octopus.components.registry.core.dto.VersionRequest
import org.octopusden.octopus.components.registry.core.dto.VersionedComponent
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.mapper.Mapper
import org.octopusden.octopus.components.registry.server.mapper.toDTO
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.model.BuildParameters
import org.octopusden.octopus.escrow.model.Tool
import org.octopusden.octopus.releng.dto.JiraComponentVersion
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/2/components")
class ComponentControllerV2(
    private val detailedComponentVersionMapper: Mapper<JiraComponentVersion, DetailedComponentVersion>,
) : BaseComponentController<ComponentV2>() {

    @GetMapping(
        "{component}/versions/{version}",
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getDetailedComponent(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String
    ): DetailedComponent {
        LOG.info("Get Component: '$component:$version'")
        return createDetailedComponent(
            component,
            version,
            componentRegistryResolver.getResolvedComponentDefinition(component, version)
                ?: throw NotFoundException("Component id $component:$version is not found")
        )
    }

    private fun resolveVCSSettings(component: String, version: String): VCSSettingsDTO {
        return with(componentRegistryResolver.getVCSSettings(component, version)) {
            VCSSettingsDTO(
                versionControlSystemRoots
                    .map {
                        VersionControlSystemRootDTO(
                            it.name,
                            it.vcsPath,
                            RepositoryType.valueOf(it.repositoryType.name),
                            it.tag,
                            it.branch,
                            it.hotfixBranch,
                        )
                    },
                externalRegistry,
            )
        }
    }

    private fun resolveJiraComponentVersion(component: String, version: String) =
        componentRegistryResolver.getJiraComponentVersion(component, version).toDTO()

    private fun createDetailedComponent(
        componentName: String,
        version: String,
        escrowModuleConfig: EscrowModuleConfig
    ): DetailedComponent {
        val jiraComponentVersion = componentRegistryResolver.getJiraComponentVersion(componentName, version)
        val detailedComponent = DetailedComponent(
            id = componentName,
            name = escrowModuleConfig.componentDisplayName,
            componentOwner = escrowModuleConfig.componentOwner,
            buildSystem = getBuildSystem(escrowModuleConfig.buildSystem),
            vcsSettings = resolveVCSSettings(componentName, version),
            jiraComponentVersion = jiraComponentVersion.toDTO(),
            detailedComponentVersion = detailedComponentVersionMapper.convert(jiraComponentVersion),
            deprecated = escrowModuleConfig.isDeprecated,
            buildFilePath = escrowModuleConfig.buildFilePath,
        )
        return with(detailedComponent) {
            releaseManager = escrowModuleConfig.releaseManager
            securityChampion = escrowModuleConfig.securityChampion
            distribution = getComponentDistribution(escrowModuleConfig)
            system = escrowModuleConfig.systemSet
            clientCode = escrowModuleConfig.clientCode
            releasesInDefaultBranch = escrowModuleConfig.releasesInDefaultBranch
            parentComponent = escrowModuleConfig.parentComponent
            buildParameters = escrowModuleConfig.buildConfiguration?.let { bc -> getBuildParametersDTO(bc) }
            escrow = escrowModuleConfig.escrow?.let { escrow -> getEscrowDTO(escrow) }
            solution = escrowModuleConfig.solution
            this
        }
    }

    private fun getBuildSystem(escrowBuildSystem: org.octopusden.octopus.escrow.BuildSystem): BuildSystem {
        return when (escrowBuildSystem) {
            org.octopusden.octopus.escrow.BuildSystem.BS2_0 -> BuildSystem.BS2_0
            org.octopusden.octopus.escrow.BuildSystem.MAVEN -> BuildSystem.MAVEN
            org.octopusden.octopus.escrow.BuildSystem.ECLIPSE_MAVEN -> BuildSystem.ECLIPSE_MAVEN
            org.octopusden.octopus.escrow.BuildSystem.GRADLE -> BuildSystem.GRADLE
            org.octopusden.octopus.escrow.BuildSystem.WHISKEY -> BuildSystem.WHISKEY
            org.octopusden.octopus.escrow.BuildSystem.PROVIDED -> BuildSystem.PROVIDED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_NOT_SUPPORTED -> BuildSystem.NOT_SUPPORTED
            org.octopusden.octopus.escrow.BuildSystem.ESCROW_PROVIDED_MANUALLY -> BuildSystem.ESCROW_PROVIDED_MANUALLY
            org.octopusden.octopus.escrow.BuildSystem.GOLANG -> BuildSystem.GOLANG
            org.octopusden.octopus.escrow.BuildSystem.IN_CONTAINER -> BuildSystem.IN_CONTAINER
        }
    }

    private fun getBuildParametersDTO(buildParameters: BuildParameters): BuildParametersDTO {
        return BuildParametersDTO(
            buildParameters.javaVersion,
            buildParameters.mavenVersion,
            buildParameters.gradleVersion,
            buildParameters.requiredProject,
            buildParameters.projectVersion,
            buildParameters.systemProperties,
            buildParameters.buildTasks,
            getToolsDTO(buildParameters.tools),
            buildParameters.buildTools
        )
    }

    private fun getEscrowDTO(escrow: Escrow): EscrowDTO {
        return EscrowDTO(
            escrow.buildTask,
            escrow.providedDependencies.toList(),
            escrow.diskSpaceRequirement.orElse(null),
            escrow.additionalSources.toList(),
            escrow.isReusable
        )
    }

    private fun getToolsDTO(tools: List<Tool>): List<ToolDTO> {
        return tools.map { tool ->
            ToolDTO(
                name = tool.name,
                escrowEnvironmentVariable = tool.escrowEnvironmentVariable,
                sourceLocation = tool.sourceLocation,
                targetLocation = tool.targetLocation,
                installScript = tool.installScript
            )
        }
    }

    @GetMapping(
        "{component}/versions/{version}/detailed-version",
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getComponentRegistryVersion(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String
    ): DetailedComponentVersion {
        LOG.info("Get Detailed Component Version: '$component:$version'")
        val jiraComponentVersion = componentRegistryResolver.getJiraComponentVersion(component, version)
        return detailedComponentVersionMapper.convert(jiraComponentVersion)
    }

    @GetMapping("{component}/maven-artifacts", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getComponentArtifactsParameters(@PathVariable("component") component: String): Map<String, ComponentArtifactConfigurationDTO> {
        return componentRegistryResolver.getMavenArtifactParameters(component)
            .map { (versionRange, artifactConfiguration) -> versionRange to artifactConfiguration.toDTO() }
            .toMap()
    }

    @GetMapping("{component}/versions/{version}/vcs-settings", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getVCSSettings(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String
    ): VCSSettingsDTO {
        LOG.info("Get VCS Settings: '$component:$version'")
        return resolveVCSSettings(component, version)
    }

    @GetMapping("{component}/versions/{version}/build-tools", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getBuildTools(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String,
        @RequestParam(name = "ignore-required", required = false, defaultValue = "false") ignoreRequired: Boolean?
    ): List<BuildTool> {
        return componentRegistryResolver.getBuildTools(component, version, ignoreRequired)
    }

    @PostMapping(
        "{component}/detailed-versions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getComponentDetailedComponentVersions(
        @PathVariable("component") component: String,
        @RequestBody versionRequest: VersionRequest
    ): DetailedComponentVersions {
        LOG.info("Get Detailed Component Versions: '$component'")
        LOG.debug("Get Detailed Component Versions: '$component:${versionRequest.versions.joinToString()}'")
        val detailedComponentVersions =
            componentRegistryResolver.getJiraComponentVersions(component, versionRequest.versions)
                .map { it.key to detailedComponentVersionMapper.convert(it.value) }
                .toMap()
        return DetailedComponentVersions(detailedComponentVersions)
    }

    @GetMapping(
        "{component}/versions/{version}/jira-component",
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getJiraComponent(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String
    ): JiraComponentVersionDTO {
        LOG.info("Get Jira ComponentVersion: '$component:$version'")
        return resolveJiraComponentVersion(component, version)
    }

    @PostMapping("find-by-artifact")
    // todo - consider removing the whole endpoint or just version specific fields, like docker,
    //  because version is not provided in this context
    fun findComponentByArtifact(@RequestBody artifact: ArtifactDependency): VersionedComponent =
        componentRegistryResolver.findComponentByArtifact(artifact)

    @PostMapping(
        "findByArtifacts",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    // todo - consider removing the whole endpoint or just version specific fields, like docker,
    //  because version is not provided in this context
    fun findComponentByArtifact(@RequestBody artifacts: Collection<ArtifactDependency>): Collection<VersionedComponent> =
        componentRegistryResolver.findComponentsByArtifact(artifacts.toSet())
            .values
            .filterNotNull()

    override var createComponentFunc: (EscrowModule) -> ComponentV2 = { escrowModule ->
        val moduleName = escrowModule.moduleName
        val escrowModuleConfig = escrowModule.moduleConfigurations[0]
        ComponentV2(moduleName, escrowModuleConfig.componentDisplayName, escrowModuleConfig.componentOwner)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ComponentControllerV2::class.java)
    }
}
