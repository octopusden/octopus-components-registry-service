package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DistributionDTO(
        @JsonProperty("explicit") val explicit: Boolean,
        @JsonProperty("external") val external: Boolean,
        @JsonProperty("GAV", defaultValue = "") val GAV: String = "",
        @JsonProperty("securityGroups") val securityGroups: SecurityGroupsDTO = SecurityGroupsDTO()
) {
    @JsonProperty("GAV")
    fun getGav() = GAV
}
