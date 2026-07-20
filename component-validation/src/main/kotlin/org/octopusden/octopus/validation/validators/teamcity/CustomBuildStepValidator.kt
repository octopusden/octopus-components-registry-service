package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.core.Validator
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * [TeamCityValidationType.HAS_CUSTOM_BUILD_STEP] — is there any uninherited ("custom") build step
 * that resolves to a Java or Maven version? Checks every uninherited step across every build
 * configuration, attached to a build template or not. [Status.WARNING] if any is found,
 * [Status.OK] otherwise — always applicable.
 */
class CustomBuildStepValidator(
    private val buildConfigurationResolver: BuildConfigurationResolver,
    private val buildStepToolVersionResolver: BuildStepToolVersionResolver,
) : Validator<TeamcityProject> {
    override val type = TeamCityValidationType.HAS_CUSTOM_BUILD_STEP

    override fun validate(input: TeamcityProject): ValidationResult {
        val found = customSteps(input).filter { buildStepToolVersionResolver.resolve(it.step).isNotEmpty() }

        return if (found.isNotEmpty()) {
            val description = found.joinToString { describe(it.configuration, it.step) }
            ValidationResult(type, Status.WARNING, "Custom build step(s) found: $description")
        } else {
            ValidationResult(type, Status.OK, "No custom step with a detected tool version found")
        }
    }

    /** Every uninherited build step, across every configuration attached or not attached to a build template. */
    private fun customSteps(project: TeamcityProject): List<BuildStepInConfiguration> {
        val attached = buildConfigurationResolver.attachedToBuildTemplate(project)
        val notAttached = buildConfigurationResolver.notAttachedToBuildTemplate(project)
        return (attached + notAttached).flatMap { configuration ->
            configuration.steps.filter { !it.inherited && !it.disabled }.map { BuildStepInConfiguration(configuration, it) }
        }
    }
}
