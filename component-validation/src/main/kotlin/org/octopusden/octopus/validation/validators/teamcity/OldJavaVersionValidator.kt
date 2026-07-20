package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.core.Validator
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * [org.octopusden.octopus.validation.validators.type.TeamCityValidationType.USES_OLD_JAVA_VERSION] — inspects every [relevantBuildSteps] step (uninherited
 * steps across every configuration, plus each attached configuration's default build step,
 * whatever its inheritance). [Status.WARNING] if any resolved [JavaVersion.isEight] — this is a
 * finding about the project's configuration, not a failure of the validation process itself, so it
 * stays a warning rather than [Status.ERROR] (reserved for when validation itself couldn't run
 * cleanly, see [org.octopusden.octopus.validation.core.ValidatorSuite] decision D6). [Status.OK] if
 * versions were resolved but none is 1.8; [Status.NOT_APPLICABLE] if there was nothing Java-relevant
 * to inspect. Unresolved (`null`) versions are ignored (decision D7).
 *
 * The message reports which build step, and which build configuration it lives in, resolved to
 * Java 1.8 -- not just the version value itself.
 */
class OldJavaVersionValidator(
    private val buildConfigurationResolver: BuildConfigurationResolver,
    private val buildStepResolver: BuildStepResolver,
    private val buildStepToolVersionResolver: BuildStepToolVersionResolver,
) : Validator<TeamcityProject> {
    override val type = TeamCityValidationType.USES_OLD_JAVA_VERSION

    override fun validate(input: TeamcityProject): ValidationResult {
        val steps = relevantBuildSteps(input, buildConfigurationResolver, buildStepResolver)
        val inspectedAnything = steps.any { buildStepToolVersionResolver.supports(it.step.type) }
        val onJavaEight = steps.filter { step ->
            buildStepToolVersionResolver.resolve(step.step).filterIsInstance<JavaVersion>().any { it.isEight }
        }

        return when {
            onJavaEight.isNotEmpty() ->
                ValidationResult(
                    type,
                    Status.WARNING,
                    "Java 1.8 found: ${onJavaEight.joinToString { describe(it.configuration, it.step) }}",
                )
            inspectedAnything ->
                ValidationResult(type, Status.OK, "No Java 1.8 usage found")
            else ->
                ValidationResult(type, Status.NOT_APPLICABLE, "Nothing Java to inspect")
        }
    }
}
