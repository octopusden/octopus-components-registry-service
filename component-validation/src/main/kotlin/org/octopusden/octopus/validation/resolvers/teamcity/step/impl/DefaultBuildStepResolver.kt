package org.octopusden.octopus.validation.resolvers.teamcity.step.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepResolver

class DefaultBuildStepResolver(
    private val catalog: TemplateCatalog,
) : BuildStepResolver {
    override fun defaultBuildStep(config: BuildConfiguration): BuildStep? {
        val defaultStepId = config.templateIds.firstNotNullOfOrNull { catalog.defaultBuildStepId(it) }
            ?: return null
        return config.steps.firstOrNull { it.id == defaultStepId }
    }

    override fun stepsOfTypes(
        config: BuildConfiguration,
        types: Set<StepType>,
    ): List<BuildStep> = config.steps.filter { it.type in types }
}
