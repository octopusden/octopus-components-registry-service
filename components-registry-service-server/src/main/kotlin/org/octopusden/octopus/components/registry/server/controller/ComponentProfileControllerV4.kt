package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.server.dto.v4.ComponentProfileResponse
import org.octopusden.octopus.components.registry.server.dto.v4.ComponentProfilesResponse
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/4")
class ComponentProfileControllerV4(
    private val catalog: ComponentProfileCatalog,
    private val availability: ProfileAvailability,
) {
    @GetMapping("/component-profiles")
    @PreAuthorize("@permissionEvaluator.hasPermission('ACCESS_COMPONENTS')")
    fun listProfiles(): ComponentProfilesResponse {
        val profiles = catalog.profiles().map { Triple(it.order, it.id, ComponentProfileResponse.from(it, availability.evaluate(it))) }
        val templates = catalog.templates().map { Triple(it.order, it.id, ComponentProfileResponse.from(it, availability.evaluate(it))) }
        return ComponentProfilesResponse((profiles + templates).sortedWith(compareBy({ it.first }, { it.second })).map { it.third })
    }
}
