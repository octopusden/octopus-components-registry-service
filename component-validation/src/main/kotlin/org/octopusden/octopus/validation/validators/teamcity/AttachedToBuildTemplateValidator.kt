package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.core.Validator
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/** [org.octopusden.octopus.validation.validators.type.TeamCityValidationType.ATTACHED_TO_BUILD_TEMPLATE] — always applicable. */
class AttachedToBuildTemplateValidator(
    private val buildConfigurationResolver: BuildConfigurationResolver,
) : Validator<TeamcityProject> {
    override val type = TeamCityValidationType.ATTACHED_TO_BUILD_TEMPLATE

    override fun validate(input: TeamcityProject): ValidationResult {
        val attached = buildConfigurationResolver.attachedToBuildTemplate(input)
        return if (attached.size == 1) {
            ValidationResult(type, Status.OK, "Attached to a build template: ${attached.first().id}")
        } else if (attached.size > 1) {
            val ids = attached.joinToString(", ") { it.id }
            ValidationResult(type, Status.WARNING, "Multiple build configurations are attached to a build template: $ids")
        } else {
            ValidationResult(type, Status.WARNING, "No build configuration is attached to a build template")
        }
    }
}
