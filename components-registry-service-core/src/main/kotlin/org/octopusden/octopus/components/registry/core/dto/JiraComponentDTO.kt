package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class JiraComponentDTO (
    @JsonProperty("projectKey") val projectKey: String,
    @JsonProperty("displayName") val displayName: String?,
    @JsonProperty("componentVersionFormat") val componentVersionFormat: ComponentVersionFormatDTO,
    @JsonProperty("componentInfo") val componentInfo: ComponentInfoDTO,
    @JsonProperty("technical") val technical: Boolean)
