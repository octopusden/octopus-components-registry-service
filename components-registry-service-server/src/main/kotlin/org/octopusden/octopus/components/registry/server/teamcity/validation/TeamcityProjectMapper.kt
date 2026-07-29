package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.springframework.stereotype.Component
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType as ExternalBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject as ExternalTeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties as ExternalProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityStep as ExternalStep
import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration as ValBuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.BuildStep as ValBuildStep
import org.octopusden.octopus.validation.dto.teamcity.Parameters as ValParameters
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject as ValTeamcityProject

/**
 * External TeamCity DTOs -> module model. Merges project + config + step params into each step and
 * maps the raw step `type` string to `StepType`.
 */
@Component
class TeamcityProjectMapper {
    fun toModel(external: ExternalTeamcityProject): ValTeamcityProject {
        val projectParams = external.parameters.toMap()
        return ValTeamcityProject(
            id = external.id,
            parameters = ValParameters(projectParams),
            buildConfigurations =
                external.buildTypes?.buildTypes.orEmpty().map { buildType ->
                    toConfiguration(buildType, projectParams)
                },
        )
    }

    private fun toConfiguration(
        buildType: ExternalBuildType,
        projectParams: Map<String, String>,
    ): ValBuildConfiguration {
        val configParams = projectParams + buildType.parameters.toMap()
        return ValBuildConfiguration(
            id = buildType.id,
            name = buildType.name,
            paused = buildType.paused == true,
            templateIds = templateIdsOf(buildType),
            parameters = ValParameters(configParams),
            steps = buildType.steps
                ?.steps
                .orEmpty()
                .map { step -> toStep(step, configParams) },
        )
    }

    private fun toStep(
        step: ExternalStep,
        configParams: Map<String, String>,
    ): ValBuildStep {
        // Effective parameters: project ⊕ config already merged in configParams, then step overlays.
        val effective = configParams + step.properties.toMap()
        return ValBuildStep(
            id = step.id,
            name = step.name,
            type = stepTypeOf(step.type),
            disabled = step.disabled == true,
            inherited = step.inherited == true,
            parameters = ValParameters(effective),
        )
    }

    private fun templateIdsOf(buildType: ExternalBuildType): Set<String> =
        buildSet {
            buildType.template?.id?.let(::add)
            buildType.templates?.buildTypes?.forEach { it.id.let(::add) }
        }

    private fun stepTypeOf(rawType: String): StepType =
        when (rawType) {
            "gradle-runner" -> StepType.GRADLE
            "Maven2" -> StepType.MAVEN
            "simpleRunner" -> StepType.COMMAND_LINE
            else -> StepType.OTHER
        }

    /** Flatten a TC properties block to name→value, keeping the first value per name. */
    private fun ExternalProperties?.toMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        this?.properties?.forEach { property -> map.putIfAbsent(property.name, property.value.orEmpty()) }
        return map
    }
}
