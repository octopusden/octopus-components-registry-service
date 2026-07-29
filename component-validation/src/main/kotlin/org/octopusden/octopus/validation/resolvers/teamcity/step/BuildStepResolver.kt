package org.octopusden.octopus.validation.resolvers.teamcity.step

import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog

interface BuildStepResolver {
    /**
     * The template's default build step (id `X`, per [TemplateCatalog.defaultBuildStepId]), or
     * `null` if [config] isn't attached to one of our build templates, or the step isn't present.
     * The returned step carries its own `inherited` flag, so callers classify inherited vs
     * overridden themselves.
     */
    fun defaultBuildStep(config: BuildConfiguration): BuildStep?

    /** Steps of the requested runner [types] within [config] — the extensible search primitive. */
    fun stepsOfTypes(
        config: BuildConfiguration,
        types: Set<StepType>,
    ): List<BuildStep>
}
