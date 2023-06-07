package org.octopusden.octopus.escrow.configuration.loader

import org.octopusden.octopus.components.registry.api.Component

interface IConfigLoader {

    ConfigObject loadModuleConfig()

    ConfigObject loadModuleConfig(Map<String, String> params)

    ConfigObject loadModuleConfigWithoutValidationForUnknownAttributes(Map<String, String> params)

    Collection<Component> loadDslDefinedComponents()
}


