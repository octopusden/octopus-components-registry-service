package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.Component
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.escrow.BuildSystem as EscrowBuildSystem
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.EscrowConfigValidator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

abstract class BaseComponentController<T : Component> {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    protected lateinit var componentRegistryResolver: ComponentRegistryResolver

    protected abstract val createComponentFunc: (escrowModule: EscrowModule) -> T

    @GetMapping
    fun getAllComponents(
        @RequestParam("vcs-path", required = false) vcsPath: String?,
        @RequestParam("build-system", required = false) buildSystem: BuildSystem?,
        @RequestParam("systems", required = false, defaultValue = "") systems: List<String>,
        @RequestParam("solution", required = false) solution: Boolean?
    ): ComponentsDTO<T> {

        val components = componentRegistryResolver.getComponents().filter { module ->
            module.moduleConfigurations.any { config ->

                val vcsPathEquals = vcsPath?.let { vcsPathValue ->
                    config.vcsSettings
                        .versionControlSystemRoots
                        .any { vcsPathValue.equals(it.vcsPath, true) }
                } ?: true

                val buildSystemEquals = buildSystem?.let { buildSystemValue ->
                    config.buildSystem == org.octopusden.octopus.escrow.BuildSystem.valueOf(buildSystemValue.name)
                } ?: true

                val solutionEquals = solution?.let { solutionValue ->
                    config.solution == solutionValue
                } ?: true

                vcsPathEquals && buildSystemEquals && solutionEquals
            }
        }
            .map {
                createComponent(it)
            }
            .filter { c ->
                if (systems.isEmpty()) {
                    true
                } else {
                    c.system?.any { s ->
                        systems.contains(s)
                    } ?: false
                }
            }

        return ComponentsDTO(components)
    }

    @GetMapping("{component}")
    fun getComponentById(@PathVariable("component") component: String): T {
        val escrowModule = componentRegistryResolver.getComponentById(component)
            ?: throw NotFoundException("Component id $component is not found")
        return createComponent(escrowModule)
    }

    @GetMapping("{component}/distribution")
    fun getComponentDistribution(@PathVariable("component") component: String): DistributionDTO =
        getDistribution(component)

    @GetMapping("{component}/versions/{version}/distribution")
    fun getComponentVersionDistribution(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String
    ): DistributionDTO {
        return getComponentDistribution(
            componentRegistryResolver.getResolvedComponentDefinition(component, version)
                ?: throw NotFoundException("Component id $component:$version is not found")
        )
    }

    private fun createComponent(escrowModule: EscrowModule): T =
        with(createComponentFunc(escrowModule)) {
            val escrowModuleConfig = escrowModule.moduleConfigurations[0]
            releaseManager = escrowModuleConfig.releaseManager
            securityChampion = escrowModuleConfig.securityChampion
            distribution = getComponentDistribution(escrowModuleConfig)
            system = escrowModuleConfig.system?.split(EscrowConfigValidator.SPLIT_PATTERN)
            clientCode = escrowModuleConfig.clientCode
            releasesInDefaultBranch = escrowModuleConfig.releasesInDefaultBranch
            solution = escrowModuleConfig.solution
            parentComponent = escrowModuleConfig.parentComponent
            this
        }

    private fun getDistribution(component: String) =
        (getComponentById(component).distribution ?: throw IllegalStateException("Distribution can not be null"))

    companion object {
        fun getComponentDistribution(escrowModuleConfig: EscrowModuleConfig): DistributionDTO {
            return with(escrowModuleConfig) {
                DistributionDTO(
                    distribution != null && distribution.explicit(),
                    distribution != null && distribution.external(),
                    distribution?.GAV() ?: "", // TODO: elvis for GAV backward compatibility, remove when all clients are updated to the latest version
                    distribution?.DEB(),
                    distribution?.RPM(),
                    SecurityGroupsDTO(distribution?.securityGroups?.read?.split(",") ?: emptyList()),
                    distribution?.DOCKER()
                )
            }
        }
    }
}
