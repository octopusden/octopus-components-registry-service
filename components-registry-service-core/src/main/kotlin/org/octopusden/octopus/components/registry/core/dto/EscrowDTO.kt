package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class EscrowDTO @JsonCreator constructor(
    @JsonProperty("providedDependencies") val providedDependencies: List<String>? = emptyList(),
    @JsonProperty("diskSpaceRequirements") val diskSpaceRequirements: Long? = null,
    @JsonProperty("additionalSources") val additionalSources: List<String>? = emptyList()
)