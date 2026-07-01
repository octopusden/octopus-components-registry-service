package org.octopusden.octopus.components.registry.server.controller

import io.swagger.v3.oas.annotations.Operation
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.Component
import org.octopusden.octopus.components.registry.core.dto.ComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.components.registry.core.dto.SecurityGroupsDTO
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.server.mapper.toDTO
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

abstract class BaseComponentController<T : Component> {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Autowired
    protected lateinit var componentRegistryResolver: ComponentRegistryResolver

    protected abstract val createComponentFunc: (escrowModule: EscrowModule) -> T

    // todo - consider removing the whole endpoint or just version specific fields, like docker,
    //  because version is not provided in this context
    @GetMapping
    fun getAllComponents(
        @RequestParam("vcs-path", required = false) vcsPath: String?,
        @RequestParam("build-system", required = false) buildSystem: BuildSystem?,
        @RequestParam("systems", required = false, defaultValue = "") systems: List<String>,
        @RequestParam("solution", required = false) solution: Boolean?,
    ): ComponentsDTO<T> {
        val components =
            componentRegistryResolver
                .getComponents()
                .filter { module ->
                    module.moduleConfigurations.any { config ->

                        // `config.vcsSettings` is a Java/Groovy platform type and can be
                        // null for components that declare no VCS roots and no externalRegistry
                        // (e.g. some library components in the DB-backed resolver). A non-null
                        // `vcs-path` query parameter must filter such components out, not 500.
                        val vcsPathEquals =
                            vcsPath?.let { vcsPathValue ->
                                config.vcsSettings
                                    ?.versionControlSystemRoots
                                    ?.any { vcsPathValue.equals(it.vcsPath, true) }
                                    ?: false
                            } ?: true

                        val buildSystemEquals =
                            buildSystem?.let { buildSystemValue ->
                                config.buildSystem ==
                                    org.octopusden.octopus.escrow.BuildSystem
                                        .valueOf(buildSystemValue.name)
                            } ?: true

                        val solutionEquals =
                            solution?.let { solutionValue ->
                                config.solution == solutionValue
                            } ?: true

                        vcsPathEquals && buildSystemEquals && solutionEquals
                    }
                }.map {
                    createComponent(it)
                }.filter { c ->
                    if (systems.isEmpty()) {
                        true
                    } else {
                        systems.any {
                            c.system.contains(it)
                        }
                    }
                }

        return ComponentsDTO(components)
    }

    // todo - consider removing the whole endpoint or just version specific fields, like docker,
    //  because version is not provided in this context
    @GetMapping("{component}")
    fun getComponentById(
        @PathVariable("component") component: String,
    ): T {
        val escrowModule =
            componentRegistryResolver.getComponentById(component)
                ?: throw NotFoundException("Component id $component is not found")
        return createComponent(escrowModule)
    }

    // DEPRECATED (2026-07, with the ADR-018 base-row amendment): the component-level distribution
    // is ambiguous without a version — it now reports the OPEN-UPPER (newest) block's values, which
    // changed this endpoint's output for components whose newest range redefines distribution. The
    // recorded prod trace shows ZERO traffic here (all real /distribution calls are per-version).
    // Use GET {component}/versions/{version}/distribution instead. Kept only for wire-compat.
    @Deprecated("Component-level distribution is ambiguous without a version; use the per-version endpoint")
    @Operation(
        deprecated = true,
        summary = "Deprecated: component-level distribution (newest-range values); use /versions/{version}/distribution",
    )
    @GetMapping("{component}/distribution")
    fun getComponentDistribution(
        @PathVariable("component") component: String,
    ): DistributionDTO = getDistribution(component)

    @GetMapping("{component}/versions/{version}/distribution")
    fun getComponentVersionDistribution(
        @PathVariable("component") component: String,
        @PathVariable("version") version: String,
    ): DistributionDTO =
        getComponentDistribution(
            componentRegistryResolver.getResolvedComponentDefinition(component, version)
                ?: throw NotFoundException("Component id $component:$version is not found"),
        )

    @Suppress("DEPRECATION") // sets the deprecated comma-joined RM/SC props on the legacy DTO for v1/v3 compatibility
    private fun createComponent(escrowModule: EscrowModule): T =
        with(createComponentFunc(escrowModule)) {
            // ADR-018: component-level scalars come from the resolved BASE representative (set by the DB
            // resolver), NOT the version-sorted moduleConfigurations[0]. Legacy in-memory loader leaves
            // componentLevelConfiguration null → fall back to [0] (already DSL-declaration order there).
            val escrowModuleConfig = escrowModule.componentLevelConfiguration ?: escrowModule.moduleConfigurations[0]
            releaseManager = escrowModuleConfig.releaseManager
            securityChampion = escrowModuleConfig.securityChampion
            distribution = getComponentDistribution(escrowModuleConfig)
            system = escrowModuleConfig.systemSet
            clientCode = escrowModuleConfig.clientCode
            releasesInDefaultBranch = escrowModuleConfig.releasesInDefaultBranch
            solution = escrowModuleConfig.solution
            parentComponent = escrowModuleConfig.parentComponent
            archived = escrowModuleConfig.archived
            doc = escrowModuleConfig.doc?.toDTO()
            escrow = escrowModuleConfig.escrow?.toDTO()
            copyright = escrowModuleConfig.copyright
            labels = escrowModuleConfig.labels?.toSet() ?: emptySet()
            this
        }

    private fun getDistribution(component: String) =
        (getComponentById(component).distribution ?: throw IllegalStateException("Distribution can not be null"))

    companion object {
        fun getComponentDistribution(escrowModuleConfig: EscrowModuleConfig): DistributionDTO =
            with(escrowModuleConfig) {
                DistributionDTO(
                    distribution != null && distribution.explicit(),
                    distribution != null && distribution.external(),
                    distribution?.GAV(),
                    distribution?.DEB(),
                    distribution?.RPM(),
                    SecurityGroupsDTO(distribution?.securityGroups?.read?.split(",") ?: emptyList()),
                    distribution?.docker(),
                )
            }
    }
}
