package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty


data class JiraComponentVersionDTO @JsonCreator constructor(
        @JsonProperty("name") val name: String,
        @JsonProperty("version") val version: String,
        @JsonProperty("component") val component: JiraComponentDTO,
        @JsonProperty("isHotfixEnabled") val isHotfixEnabled: Boolean
)
