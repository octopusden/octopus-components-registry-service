package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
class ComponentV2(
    @JsonProperty(value = "id") id: String,
    @JsonProperty(value = "name") name: String?,
    @JsonProperty(value = "componentOwner") componentOwner: String
) : Component(id, name, componentOwner) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComponentV2) return false
        return super.equals(other)
    }
}
