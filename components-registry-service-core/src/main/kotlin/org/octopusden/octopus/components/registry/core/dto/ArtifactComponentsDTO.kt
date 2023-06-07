package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactComponentsDTO @JsonCreator constructor(
    @JsonProperty("artifactComponents") val artifactComponents: Collection<ArtifactComponentDTO>
)
