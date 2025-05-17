package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.ComponentV3
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/3/components")
class ComponentControllerV3(
    private val componentRegistryResolver: ComponentRegistryResolver
) {
    /**
     * Get all components.
     */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    // todo - consider removing the whole endpoint or just version specific fields, like docker,
    //  because version is not provided in this context

    fun getAllComponents(): Collection<ComponentV3> {
        return componentRegistryResolver.getComponents().map { escrowModule ->
            //TODO Check/Discuss if display name and owner should be in escrowModule (not versioned part of Component)
            ComponentV3(
                ComponentV2(
                    escrowModule.moduleName,
                    escrowModule.moduleName,
                    escrowModule.moduleConfigurations.find { it.componentOwner != null }!!.componentOwner
                ),
                escrowModule.moduleConfigurations.map { Pair(it.versionRangeString, it.toVersionedComponent()) }.toMap()
            )
        }
    }

    @PostMapping(
        "find-by-artifacts",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun findByArtifacts(@RequestBody artifacts: Set<ArtifactDependency>): ArtifactComponentsDTO {
        val artifactComponents = componentRegistryResolver.findComponentsByArtifact(artifacts)
            .entries
            .map { (artifact, component) -> ArtifactComponentDTO(artifact, component) }
            .toSet()
        // todo - recalc docker with component
        return ArtifactComponentsDTO(artifactComponents)
    }

    @PostMapping(
        "find-by-docker-images",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun findComponentsByDockerImages(@RequestBody images: Set<Image>): Set<ComponentImage> {
        return componentRegistryResolver.findComponentsByDockerImages(images)
    }


}
