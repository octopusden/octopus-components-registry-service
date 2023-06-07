package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ServiceStatusDTO
import org.octopusden.octopus.components.registry.server.service.ComponentsRegistryService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/2/components-registry/service")
class ComponentsRegistryServiceController(
    private val componentsRegistryService: ComponentsRegistryService
) {

    @PutMapping("updateCache")
    fun updateConfigCache() = componentsRegistryService.updateConfigCache()

    @GetMapping("status", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getComponentsRegistryStatus(): ServiceStatusDTO =
        componentsRegistryService.getComponentsRegistryStatus()

    @GetMapping("ping")
    fun ping(): String {
        return "Pong"
    }
}
