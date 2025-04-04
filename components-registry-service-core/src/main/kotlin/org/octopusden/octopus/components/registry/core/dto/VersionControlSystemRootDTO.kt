package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class VersionControlSystemRootDTO @JsonCreator constructor(
    @JsonProperty("name") val name: String,
    @JsonProperty("vcsPath") val vcsPath: String,
    @JsonProperty("type") val type: RepositoryType,
    @JsonProperty("tag") val tag: String?,
    @JsonProperty("branch") val branch: String,
    @JsonProperty("hotfixBranch") val hotfixBranch: String?,
)
