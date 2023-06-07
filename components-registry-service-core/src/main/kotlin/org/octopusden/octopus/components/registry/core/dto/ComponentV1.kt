package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
class ComponentV1(
    @JsonProperty(value = "id") id: String,
    @JsonProperty(value = "componentDisplayName") name: String?,
    @JsonProperty(value = "componentOwner") componentOwner: String
) : Component(id, name, componentOwner) {
    @JsonProperty(value = "componentDisplayName")
    fun getComponentDisplayName() = name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ComponentV1) return false
        return super.equals(other)
    }
}
