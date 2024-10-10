package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool

@JsonIgnoreProperties(ignoreUnknown = true)
data class BuildParametersDTO @JsonCreator constructor(
    @JsonProperty("javaVersion") val javaVersion: String? = null,
    @JsonProperty("mavenVersion") val mavenVersion: String? = null,
    @JsonProperty("gradleVersion") val gradleVersion: String? = null,
    @JsonProperty("requiredProject") val requiredProject: Boolean? = null,
    @JsonProperty("projectVersion") val projectVersion: String? = null,
    @JsonProperty("systemProperties") val systemProperties: String? = null,
    @JsonProperty("buildTasks") val buildTasks: String? = null,
    @JsonProperty("tools") val tools: List<ToolDTO>? = emptyList(),
    @JsonProperty("buildTools") val buildTools: Collection<BuildTool>? = emptyList(),
)