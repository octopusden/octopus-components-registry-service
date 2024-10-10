package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
class DetailedComponent(
    @JsonProperty(value = "id") id: String,
    @JsonProperty(value = "name") name: String?,
    @JsonProperty(value = "componentOwner") componentOwner: String,
    @JsonProperty("buildSystem") val buildSystem: BuildSystem,
    @JsonProperty("vcsSettings") val vcsSettings: VCSSettingsDTO,
    @JsonProperty("jiraComponentVersion") val jiraComponentVersion: JiraComponentVersionDTO,
    @JsonProperty("detailedComponentVersion") val detailedComponentVersion: DetailedComponentVersion
) : Component(id, name, componentOwner) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetailedComponent) return false
        if (buildSystem != other.buildSystem) return false
        if (vcsSettings != other.vcsSettings) return false
        if (jiraComponentVersion != other.jiraComponentVersion) return false
        if (detailedComponentVersion != other.detailedComponentVersion) return false
        return super.equals(other)
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (buildSystem.hashCode())
        result = 31 * result + (vcsSettings.hashCode())
        result = 31 * result + (jiraComponentVersion.hashCode())
        result = 31 * result + (detailedComponentVersion.hashCode())
        return result
    }

    override fun toString(): String {
        return super.toString() +
                ", buildSystem=$buildSystem)" +
                ", vcsSettings=${vcsSettings}" +
                ", jiraComponentVersion=${jiraComponentVersion}" +
                ", detailedComponentVersion=${detailedComponentVersion}"
    }

}
