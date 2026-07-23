package org.octopusden.octopus.validation.dto.teamcity

/**
 * The module's own TeamCity project model — independent of the external TeamCity client. Mapping
 * external client DTOs into this model is a server concern, out of scope for this module
 * (decision D11).
 */
data class TeamcityProject(
    val id: String,
    val parameters: Parameters,
    val buildConfigurations: List<BuildConfiguration>,
)
