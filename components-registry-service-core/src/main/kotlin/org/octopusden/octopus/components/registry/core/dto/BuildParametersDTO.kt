package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool

@JsonIgnoreProperties(ignoreUnknown = true)
data class BuildParametersDTO @JsonCreator constructor(
    @JsonProperty("javaVersion") val javaVersion: String?,
    @JsonProperty("mavenVersion") val mavenVersion: String?,
    @JsonProperty("gradleVersion") val gradleVersion: String?,
    @JsonProperty("requiredProject") val requiredProject: Boolean?,
    @JsonProperty("projectVersion") val projectVersion: String?,
    @JsonProperty("systemProperties") val systemProperties: String?,
    @JsonProperty("buildTasks") val buildTasks: String?,
    @JsonProperty("tools") val tools: List<ToolDTO>?,
    @JsonProperty("buildTools") val buildTools: Collection<BuildTool>?,
)