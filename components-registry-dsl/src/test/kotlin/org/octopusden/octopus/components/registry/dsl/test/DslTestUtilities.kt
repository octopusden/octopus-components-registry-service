package org.octopusden.octopus.components.registry.dsl.test

import org.octopusden.octopus.components.registry.api.Component
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.dsl.script.ComponentsRegistryScriptRunner
import java.util.*

fun registryDsl(closure: ()-> Unit): Map<String, Component> {
    return registryDsl(closure, Collections.emptyMap())
}
fun registryDsl(closure: ()-> Unit, productTypesMap: Map<String, ProductTypes>): Map<String, Component> {
    ComponentsRegistryScriptRunner.getCurrentRegistry().clear()
    ComponentsRegistryScriptRunner.getProductTypeMap().clear()
    ComponentsRegistryScriptRunner.getProductTypeMap().putAll(productTypesMap)
    closure()
    return ComponentsRegistryScriptRunner.getCurrentRegistry().map { it.name to it }.toMap()
}