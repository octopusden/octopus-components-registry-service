package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ComponentImage @JsonCreator constructor(
    @JsonProperty("componentId") val componentId: String,
    @JsonProperty("componentVersion") val componentVersion: String,
    @JsonProperty("image") val image: Image
)
