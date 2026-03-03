package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArtifactComponentDTO(
    @JsonProperty("artifact") val artifact: ArtifactDependency,
    @JsonProperty("component") val component: VersionedComponent?
)
