package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.core.Validator
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.dto.teamcity.ToolVersion
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * The "more than one distinct version of this tool" question — instantiated twice:
 * [TeamCityValidationType.MULTIPLE_JAVA_VERSIONS] (`{ it is JavaVersion }`, "Java") and
 * [TeamCityValidationType.MULTIPLE_MAVEN_VERSIONS] (`{ it is MavenVersion }`, "Maven").
 *
 * Inspects every [relevantBuildSteps] step. [Status.WARNING] if more than one distinct version
 * accepted by [isRelevantVersion] resolves across those steps, [Status.OK] if zero or one does,
 * [Status.NOT_APPLICABLE] if there was nothing relevant to inspect.
 */
class MultipleVersionValidator(
    override val type: TeamCityValidationType,
    private val buildConfigurationResolver: BuildConfigurationResolver,
    private val buildStepResolver: BuildStepResolver,
    private val buildStepToolVersionResolver: BuildStepToolVersionResolver,
    private val toolName: String,
    private val isRelevantVersion: (ToolVersion) -> Boolean,
) : Validator<TeamcityProject> {
    override fun validate(input: TeamcityProject): ValidationResult {
        val steps = relevantBuildSteps(input, buildConfigurationResolver, buildStepResolver)
        val inspectedAnything = steps.any { buildStepToolVersionResolver.supports(it.step.type) }
        val stepsByVersion = steps
            .flatMap { step -> buildStepToolVersionResolver.resolve(step.step).filter(isRelevantVersion).map { it to step } }
            .groupBy({ it.first }, { it.second })

        return when {
            stepsByVersion.size > 1 ->
                ValidationResult(
                    type,
                    Status.WARNING,
                    "Multiple $toolName versions found: " +
                        stepsByVersion.entries.joinToString { (version, atSteps) ->
                            "${version.raw} (${atSteps.joinToString { describe(it.configuration, it.step) }})"
                        },
                )
            inspectedAnything ->
                ValidationResult(type, Status.OK, "At most one $toolName version found")
            else ->
                ValidationResult(type, Status.NOT_APPLICABLE, "Nothing $toolName to inspect")
        }
    }
}
