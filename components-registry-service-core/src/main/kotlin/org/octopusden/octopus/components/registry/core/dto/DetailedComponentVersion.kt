package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DetailedComponentVersion (
    @JsonProperty("component") val component: String,
    @JsonProperty("minorVersion") val minorVersion: ComponentRegistryVersion,
    @JsonProperty("lineVersion") val lineVersion: ComponentRegistryVersion,
    @JsonProperty("buildVersion") val buildVersion: ComponentRegistryVersion,
    @JsonProperty("rcVersion") val rcVersion: ComponentRegistryVersion,
    @JsonProperty("releaseVersion") val releaseVersion: ComponentRegistryVersion,
    @JsonProperty("hotfixVersion") val hotfixVersion: ComponentRegistryVersion?
)
