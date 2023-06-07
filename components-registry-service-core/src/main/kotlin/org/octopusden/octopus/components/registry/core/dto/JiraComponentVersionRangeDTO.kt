package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class JiraComponentVersionRangeDTO @JsonCreator constructor(
    @JsonProperty("componentName") val componentName: String,
    @JsonProperty("versionRange") val versionRange: String,
    @JsonProperty("component") val component: JiraComponentDTO,
    @JsonProperty("distribution") val distribution: DistributionDTO,
    @JsonProperty("vcsSettings") val vcsSettings: VCSSettingsDTO
)
