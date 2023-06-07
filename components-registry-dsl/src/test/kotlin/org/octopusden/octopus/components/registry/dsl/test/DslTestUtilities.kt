package org.octopusden.octopus.components.registry.dsl.test

import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner

fun registryDsl(closure: ()-> Unit): Map<String, Component> {
    ComponentsRegistryScriptRunner.getCurrentRegistry().clear()
    closure()
    return ComponentsRegistryScriptRunner.getCurrentRegistry().map { it.name to it }.toMap()
}