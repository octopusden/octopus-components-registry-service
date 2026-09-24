package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VersionControlSystemRootDTO
// Appended with defaults; @JvmOverloads keeps the six-parameter constructor for Java/Groovy callers.
@JvmOverloads
constructor(
    @JsonProperty("name") val name: String,
    @JsonProperty("vcsPath") val vcsPath: String,
    @JsonProperty("type") val type: RepositoryType,
    @JsonProperty("tag") val tag: String?,
    @JsonProperty("branch") val branch: String,
    @JsonProperty("hotfixBranch") val hotfixBranch: String?,
    @JsonProperty("sourcePath") val sourcePath: String? = null,
    @JsonProperty("checkoutDirectory") val checkoutDirectory: String? = null,
)
