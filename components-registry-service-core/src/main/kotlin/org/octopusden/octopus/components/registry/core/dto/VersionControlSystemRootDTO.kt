package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VersionControlSystemRootDTO(
    @JsonProperty("name") val name: String,
    @JsonProperty("vcsPath") val vcsPath: String,
    @JsonProperty("type") val type: RepositoryType,
    @JsonProperty("tag") val tag: String?,
    @JsonProperty("branch") val branch: String,
    @JsonProperty("hotfixBranch") val hotfixBranch: String?,
    @JsonProperty("sourcePath") val sourcePath: String? = null,
    @JsonProperty("checkoutDirectory") val checkoutDirectory: String? = null,
) {
    // The six-parameter constructor Java/Groovy callers compile against. Not @JvmOverloads: its copies carry the
    // parameters' @JsonProperty and plain Jackson then rejects conflicting creators; DISABLED keeps this one out.
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    constructor(
        name: String,
        vcsPath: String,
        type: RepositoryType,
        tag: String?,
        branch: String,
        hotfixBranch: String?,
    ) : this(name, vcsPath, type, tag, branch, hotfixBranch, null, null)
}
