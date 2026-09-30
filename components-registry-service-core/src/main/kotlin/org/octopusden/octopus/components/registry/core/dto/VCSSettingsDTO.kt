package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class VCSSettingsDTO(
    @JsonProperty("versionControlSystemRoots") val versionControlSystemRoots: List<VersionControlSystemRootDTO> = emptyList(),
    @JsonProperty("externalRegistry") val externalRegistry: String? = null,
    // Property-level NON_NULL: the other properties keep serializing as before (externalRegistry: null).
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("buildWorkingDirectory") val buildWorkingDirectory: String? = null,
) {
    // The two-parameter constructor Java/Groovy callers compile against; see VersionControlSystemRootDTO for why not @JvmOverloads.
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    constructor(
        versionControlSystemRoots: List<VersionControlSystemRootDTO>,
        externalRegistry: String?,
    ) : this(versionControlSystemRoots, externalRegistry, null)
}
