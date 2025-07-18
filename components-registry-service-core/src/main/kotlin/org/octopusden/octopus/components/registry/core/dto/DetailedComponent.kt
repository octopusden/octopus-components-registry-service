package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Objects

@JsonIgnoreProperties(ignoreUnknown = true)
class DetailedComponent(
    @JsonProperty(value = "id") id: String,
    @JsonProperty(value = "name") name: String?,
    @JsonProperty(value = "componentOwner") componentOwner: String,
    @JsonProperty("buildSystem") val buildSystem: BuildSystem,
    @JsonProperty("vcsSettings") val vcsSettings: VCSSettingsDTO,
    @JsonProperty("jiraComponentVersion") val jiraComponentVersion: JiraComponentVersionDTO,
    @JsonProperty("detailedComponentVersion") val detailedComponentVersion: DetailedComponentVersion,
    @JsonProperty("deprecated") val deprecated: Boolean,
    @JsonProperty("buildFilePath") val buildFilePath: String?,
) : Component(id, name, componentOwner) {
    var buildParameters: BuildParametersDTO? = null
    var escrow: EscrowDTO? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetailedComponent) return false
        return buildSystem == other.buildSystem &&
                vcsSettings == other.vcsSettings &&
                jiraComponentVersion == other.jiraComponentVersion &&
                detailedComponentVersion == other.detailedComponentVersion &&
                deprecated == other.deprecated &&
                buildParameters == other.buildParameters &&
                buildFilePath == other.buildFilePath &&
                escrow == other.escrow &&
                super.equals(other)
    }

    override fun hashCode(): Int {
        return Objects.hash(
            super.hashCode(),
            buildSystem,
            vcsSettings,
            jiraComponentVersion,
            detailedComponentVersion,
            buildParameters,
            deprecated,
            buildFilePath,
            escrow
        )
    }

    override fun toString(): String {
        return super.toString() +
                ", buildSystem=$buildSystem)" +
                ", vcsSettings=${vcsSettings}" +
                ", jiraComponentVersion=${jiraComponentVersion}" +
                ", detailedComponentVersion=${detailedComponentVersion}" +
                ", buildParameters=${buildParameters}" +
                ", deprecated=${deprecated}" +
                ", buildFilePath=${buildFilePath}" +
                ", escrow=${escrow}"
    }

}
