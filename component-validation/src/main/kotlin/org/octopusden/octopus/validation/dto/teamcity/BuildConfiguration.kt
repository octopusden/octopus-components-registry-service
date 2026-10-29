package org.octopusden.octopus.validation.dto.teamcity

/** One build configuration within a [TeamcityProject]. */
data class BuildConfiguration(
    val id: String,
    val name: String?,
    val paused: Boolean,
    val templateIds: Set<String>,
    val parameters: Parameters,
    val steps: List<BuildStep>,
) {
    fun inheritsFrom(templateId: String): Boolean = templateId in templateIds
}
