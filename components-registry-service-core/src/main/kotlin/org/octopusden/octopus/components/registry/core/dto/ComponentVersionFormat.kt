package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ComponentVersionFormatDTO @JsonCreator constructor(
        @JsonProperty("majorVersionFormat") val majorVersionFormat: String,
        @JsonProperty("releaseVersionFormat") val releaseVersionFormat: String,
        @JsonProperty("buildVersionFormat") val buildVersionFormat: String,
        @JsonProperty("lineVersionFormat") val lineVersionFormat: String,
        @JsonProperty("hotfixVersionFormat") val hotfixVersionFormat: String?,
        )
