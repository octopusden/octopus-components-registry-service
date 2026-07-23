package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.dto.teamcity.BuildConfiguration
import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepResolver

/**
 * A [step] paired with the [configuration] it was found in. [BuildStep] itself carries no back
 * reference to its owning [BuildConfiguration], so this pairing is how validators that need to
 * report "which build step, in which build configuration" keep that association once steps from
 * multiple configurations get gathered together.
 */
internal data class BuildStepInConfiguration(
    val configuration: BuildConfiguration,
    val step: BuildStep,
)

/**
 * Every uninherited ("custom") build step, across every configuration attached or not attached to
 * a build template, plus each attached configuration's default build step (whatever its
 * inheritance) — de-duplicated by (configuration, step). Shared by [OldJavaVersionValidator] and
 * [MultipleVersionValidator], which both inspect the same step population and differ only in what
 * they conclude from the resolved tool versions.
 */
internal fun relevantBuildSteps(
    project: TeamcityProject,
    buildConfigurationResolver: BuildConfigurationResolver,
    buildStepResolver: BuildStepResolver,
): List<BuildStepInConfiguration> {
    val attached = buildConfigurationResolver.attachedToBuildTemplate(project)
    val notAttached = buildConfigurationResolver.notAttachedToBuildTemplate(project)
    val customSteps = (attached + notAttached).flatMap { configuration ->
        configuration.steps.filter { !it.inherited }.map { BuildStepInConfiguration(configuration, it) }
    }
    val defaultSteps = attached.mapNotNull { configuration ->
        buildStepResolver.defaultBuildStep(configuration)?.let { BuildStepInConfiguration(configuration, it) }
    }
    return (customSteps + defaultSteps).filter { !it.step.disabled }.distinct()
}

/** "step name (step id) in configuration name (configuration id)" -- the shared label for reports. */
internal fun describe(
    configuration: BuildConfiguration,
    step: BuildStep,
): String = "- ${step.id} in ${configuration.id}"
