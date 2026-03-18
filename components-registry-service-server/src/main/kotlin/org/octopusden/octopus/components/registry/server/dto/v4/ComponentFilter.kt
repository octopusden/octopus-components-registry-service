package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentFilter(
    val system: String? = null,
    val productType: String? = null,
    val archived: Boolean? = null,
    val search: String? = null,
)
