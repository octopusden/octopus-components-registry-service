package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.core.Validator
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepResolver
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * [org.octopusden.octopus.validation.validators.type.TeamCityValidationType.OVERRIDES_DEFAULT_BUILD_STEP] — [Status.NOT_APPLICABLE] if no config is
 * attached to a build template, [Status.WARNING] if any attached config's default step is
 * overridden, [Status.OK] if all are inherited.
 */
class OverridesDefaultBuildStepValidator(
    private val buildConfigurationResolver: BuildConfigurationResolver,
    private val buildStepResolver: BuildStepResolver,
) : Validator<TeamcityProject> {
    override val type = TeamCityValidationType.OVERRIDES_DEFAULT_BUILD_STEP

    override fun validate(input: TeamcityProject): ValidationResult {
        val attached = buildConfigurationResolver.attachedToBuildTemplate(input)
        if (attached.isEmpty()) {
            return ValidationResult(type, Status.NOT_APPLICABLE, "No build configuration is attached to a build template")
        }

        val overridden = attached.filter { buildStepResolver.defaultBuildStep(it)?.inherited == false }
        return if (overridden.isNotEmpty()) {
            ValidationResult(
                type,
                Status.WARNING,
                "Default build step overridden in build configuration(s): ${overridden.joinToString(", ") { it.id }}",
            )
        } else {
            ValidationResult(type, Status.OK, "Default build step inherited in all attached configurations")
        }
    }
}
