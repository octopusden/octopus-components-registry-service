package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class ComponentInfoDTO @JsonCreator constructor(
        @JsonProperty("versionPrefix") val versionPrefix: String,
        @JsonProperty("versionFormat") val versionFormat: String)
