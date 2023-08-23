package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class DistributionDTO(
        @JsonProperty("explicit") val explicit: Boolean,
        @JsonProperty("external") val external: Boolean,
        @JsonProperty("GAV") val gav: String? = null,
        @JsonProperty("DEB") val deb: String? = null,
        @JsonProperty("RPM") val rpm: String? = null,
        @JsonProperty("securityGroups") val securityGroups: SecurityGroupsDTO = SecurityGroupsDTO()
)
