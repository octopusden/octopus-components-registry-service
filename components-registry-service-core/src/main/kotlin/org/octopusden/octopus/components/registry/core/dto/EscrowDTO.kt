package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class EscrowDTO @JsonCreator constructor(
    @JsonProperty("buildTask") val buildTask: String? = null,
    @JsonProperty("providedDependencies") val providedDependencies: List<String>? = emptyList(),
    @JsonProperty("diskSpaceRequirement") val diskSpaceRequirement: Long? = null,
    @JsonProperty("additionalSources") val additionalSources: List<String>? = emptyList(),
    @JsonProperty("isReusable") val isReusable: Boolean,
    @JsonProperty("generation")
    val generation: EscrowGenerationMode
)