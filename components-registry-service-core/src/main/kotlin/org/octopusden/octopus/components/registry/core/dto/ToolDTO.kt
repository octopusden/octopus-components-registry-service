package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolDTO @JsonCreator constructor(
    @JsonProperty("name") val name: String,
    @JsonProperty("escrowEnvironmentVariable") val escrowEnvironmentVariable: String,
    @JsonProperty("sourceLocation") val sourceLocation: String,
    @JsonProperty("targetLocation") val targetLocation: String,
    @JsonProperty("installScript") val installScript: String? = null,
)
