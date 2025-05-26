package org.octopusden.octopus.components.registry.core.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.octopusden.octopus.components.registry.core.exceptions.VCSConfigurationException

@JsonIgnoreProperties(ignoreUnknown = true)
data class VCSSettingsDTO @JsonCreator constructor(
    @JsonProperty("versionControlSystemRoots") val versionControlSystemRoots: List<VersionControlSystemRootDTO> = emptyList(),
    @JsonProperty("externalRegistry") val externalRegistry: String? = null,
) {
    fun hasNoConfiguredVCSRoot(): Boolean {
        return versionControlSystemRoots.isEmpty() || versionControlSystemRoots.size == 1 && versionControlSystemRoots[0].vcsPath.isNullOrEmpty()
    }
    fun getSingleVCSRoot(): VersionControlSystemRootDTO {
        if (versionControlSystemRoots.isEmpty()) {
            throw VCSConfigurationException("No VCS Roots are defined in the component")
        }
        if (versionControlSystemRoots.size != 1) {
            throw VCSConfigurationException("Several VCS Roots $versionControlSystemRoots are not supported for the component")
        }
        return versionControlSystemRoots[0]
    }
}
