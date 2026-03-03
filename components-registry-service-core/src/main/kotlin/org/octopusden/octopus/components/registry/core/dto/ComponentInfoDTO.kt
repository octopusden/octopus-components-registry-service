package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class ComponentInfoDTO (
        @JsonProperty("versionPrefix") val versionPrefix: String,
        @JsonProperty("versionFormat") val versionFormat: String)
