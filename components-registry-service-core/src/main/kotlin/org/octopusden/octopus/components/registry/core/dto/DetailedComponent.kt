package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
class DetailedComponent(
    @JsonProperty(value = "id") id: String,
    @JsonProperty(value = "name") name: String?,
    @JsonProperty(value = "componentOwner") componentOwner: String,
) : Component(id, name, componentOwner) {
    var buildSystem: BuildSystem? = null
    var vcsSettings: VCSSettingsDTO? = null
    var jiraComponentVersion: JiraComponentVersionDTO? = null
    var detailedComponentVersion: DetailedComponentVersion? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetailedComponent) return false
        if (buildSystem != other.buildSystem) return false
        if (vcsSettings != other.vcsSettings) return false
        if (jiraComponentVersion != other.jiraComponentVersion) return false
        return super.equals(other)
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (buildSystem?.hashCode() ?: 0)
        result = 31 * result + (vcsSettings?.hashCode() ?: 0)
        result = 31 * result + (jiraComponentVersion?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return super.toString() +
                ", buildSystem=$buildSystem)" +
                ", vcsSettings=${vcsSettings}" +
                ", jiraComponentVersion=${jiraComponentVersion}"
    }

}
