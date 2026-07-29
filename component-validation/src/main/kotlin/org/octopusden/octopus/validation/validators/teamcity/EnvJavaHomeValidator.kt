package org.octopusden.octopus.validation.validators.teamcity

import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.core.Validator
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.TeamcityProject
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.BuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.JavaHomeReferenceResolver
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * [TeamCityValidationType.JAVA_HOME_NOT_FROM_ENV] — inspects every [relevantBuildSteps] step that
 * resolves a Java version and asks: did that step's java-home go through the standard
 * `%env.JAVA_HOME%` reference? A step whose java-home resolves to a version but never references
 * `%env.JAVA_HOME%` is pointing at a specific JDK directly (e.g. `target.jdk.home = %env.JDK_17%`),
 * bypassing the agent's configured default — a configuration finding, hence [Status.WARNING].
 *
 * Mirrors [OldJavaVersionValidator]/[MultipleVersionValidator]: [Status.OK] if Java versions were
 * resolved and every one of them came via `%env.JAVA_HOME%`, [Status.NOT_APPLICABLE] if nothing
 * Java-relevant was found to inspect.
 */
class EnvJavaHomeValidator(
    private val buildConfigurationResolver: BuildConfigurationResolver,
    private val buildStepResolver: BuildStepResolver,
    private val buildStepToolVersionResolver: BuildStepToolVersionResolver,
    private val javaHomeReferenceResolver: JavaHomeReferenceResolver,
) : Validator<TeamcityProject> {
    override val type = TeamCityValidationType.JAVA_HOME_NOT_FROM_ENV

    override fun validate(input: TeamcityProject): ValidationResult {
        val steps = relevantBuildSteps(input, buildConfigurationResolver, buildStepResolver)
        val javaSteps =
            steps.filter { step ->
                buildStepToolVersionResolver.resolve(step.step).any { it is JavaVersion }
            }
        val notFromEnv =
            javaSteps.filter { step ->
                ENV_JAVA_HOME !in javaHomeReferenceResolver.javaHomeReferences(step.step)
            }

        return when {
            notFromEnv.isNotEmpty() ->
                ValidationResult(
                    type,
                    Status.WARNING,
                    "Java version not resolved from %env.JAVA_HOME%:\n" +
                        notFromEnv.joinToString("\n") { describe(it.configuration, it.step) },
                )
            javaSteps.isNotEmpty() ->
                ValidationResult(type, Status.OK, "All Java versions are resolved from %env.JAVA_HOME%")
            else ->
                ValidationResult(type, Status.NOT_APPLICABLE, "Nothing Java to inspect")
        }
    }

    private companion object {
        const val ENV_JAVA_HOME = "env.JAVA_HOME"
    }
}
