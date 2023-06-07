package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ComponentArtifactConfigurationDTO @JsonCreator constructor(
    @JsonProperty("groupPattern") val groupPattern: String?,
    @JsonProperty("artifactPattern") val artifactPattern: String?
)
