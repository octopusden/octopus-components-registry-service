package org.octopusden.octopus.components.registry.server.dto.v4

data class ComponentCreateRequest(
    val name: String,
    val displayName: String? = null,
    val componentOwner: String? = null,
    val productType: String? = null,
    val system: Set<String> = emptySet(),
    val clientCode: String? = null,
    val solution: Boolean? = null,
    val parentComponentName: String? = null,
    val archived: Boolean = false,
    val metadata: Map<String, Any?> = emptyMap(),
)
