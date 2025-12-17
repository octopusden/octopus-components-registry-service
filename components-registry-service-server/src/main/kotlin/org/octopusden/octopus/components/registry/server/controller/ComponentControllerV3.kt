package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactComponentsDTO
import org.octopusden.octopus.components.registry.core.dto.ArtifactDependency
import org.octopusden.octopus.components.registry.core.dto.ComponentImage
import org.octopusden.octopus.components.registry.core.dto.ComponentV2
import org.octopusden.octopus.components.registry.core.dto.ComponentV3
import org.octopusden.octopus.components.registry.core.dto.Image
import org.octopusden.octopus.components.registry.server.service.ComponentRegistryResolver
import org.octopusden.octopus.components.registry.server.service.CopyrightService
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/3/components")
class ComponentControllerV3(
    private val componentRegistryResolver: ComponentRegistryResolver,
    private val copyrightService: CopyrightService,
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
            val baseConfiguration  = escrowModule.moduleConfigurations.find { it.componentOwner != null }!!

            val componentV2 = ComponentV2(
                id = escrowModule.moduleName,
                name = escrowModule.moduleName,
                componentOwner = baseConfiguration.componentOwner,
            ).apply {
                copyright = baseConfiguration.copyright
            }

            ComponentV3(
                componentV2,
                escrowModule.moduleConfigurations
                    .associate { it.versionRangeString to it.toVersionedComponent() }
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

    @GetMapping(
        "/{component}/copyright",
        produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE]
    )
    fun getCopyrightByComponent(@PathVariable component: String): ResponseEntity<Resource> {
        val resource = copyrightService.getCopyrightAsResource(component)

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=$DOWNLOADING_COPYRIGHT_FILE_NAME"
            )
            .body(resource)
    }

    companion object {
        private const val DOWNLOADING_COPYRIGHT_FILE_NAME = "COPYRIGHT"
    }
}
