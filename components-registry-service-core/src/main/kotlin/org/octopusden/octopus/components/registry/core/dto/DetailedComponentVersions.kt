package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class DetailedComponentVersions(
        @JsonProperty("versions") val versions: Map<String, DetailedComponentVersion> = emptyMap())
