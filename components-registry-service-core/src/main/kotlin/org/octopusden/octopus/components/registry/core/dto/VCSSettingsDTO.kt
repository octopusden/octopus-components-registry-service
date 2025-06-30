package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class VCSSettingsDTO @JsonCreator constructor(
    @JsonProperty("versionControlSystemRoots") val versionControlSystemRoots: List<VersionControlSystemRootDTO> = emptyList(),
    @JsonProperty("externalRegistry") val externalRegistry: String? = null,
)