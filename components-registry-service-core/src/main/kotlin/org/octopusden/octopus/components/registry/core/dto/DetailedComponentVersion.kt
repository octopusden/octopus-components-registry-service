package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailedComponentVersion (
    @JsonProperty("component") val component: String,
    @JsonProperty("minorVersion") val minorVersion: ComponentRegistryVersion,
    @JsonProperty("lineVersion") val lineVersion: ComponentRegistryVersion,
    @JsonProperty("buildVersion") val buildVersion: ComponentRegistryVersion,
    @JsonProperty("rcVersion") val rcVersion: ComponentRegistryVersion,
    @JsonProperty("releaseVersion") val releaseVersion: ComponentRegistryVersion
)
