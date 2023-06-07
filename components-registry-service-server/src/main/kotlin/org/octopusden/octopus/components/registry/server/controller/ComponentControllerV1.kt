package org.octopusden.octopus.components.registry.server.controller

import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.escrow.configuration.model.EscrowModule
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/1/components")
class ComponentControllerV1 : BaseComponentController<ComponentV1>() {
    override var createComponentFunc: (EscrowModule) -> ComponentV1 = { escrowModule ->
        val moduleName = escrowModule.moduleName
        val escrowModuleConfig = escrowModule.moduleConfigurations[0]
        ComponentV1(
            moduleName,
            escrowModuleConfig.componentDisplayName ?: moduleName,
            escrowModuleConfig.componentOwner
        )
    }
}
