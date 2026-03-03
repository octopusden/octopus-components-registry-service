package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonProperty


data class JiraComponentVersionDTO (
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("component") val component: JiraComponentDTO
)
