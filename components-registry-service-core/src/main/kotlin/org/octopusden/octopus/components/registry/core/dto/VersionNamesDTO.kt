package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class VersionNamesDTO
@JsonCreator
constructor(
    @JsonProperty("serviceBranch") val serviceBranch: String,
    @JsonProperty("service") val service: String,
    @JsonProperty("minor") val minor: String
)