package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ComponentRegistryVersion(
    @JsonProperty("type") val type: ComponentVersionType,
    @JsonProperty("version") val version: String,
    @JsonProperty("jiraVersion") val jiraVersion: String?
)
