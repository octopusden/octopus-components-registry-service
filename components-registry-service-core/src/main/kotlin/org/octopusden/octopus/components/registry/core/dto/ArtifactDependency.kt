package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactDependency (
    @JsonProperty("group") val group: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("version") val version: String
)
