package org.octopusden.octopus.validation.dto.teamcity

/** One build step within a [BuildConfiguration]. */
data class BuildStep(
    val id: String,
    val name: String,
    val type: StepType,
    val disabled: Boolean,
    val inherited: Boolean,
    val parameters: Parameters,
)
