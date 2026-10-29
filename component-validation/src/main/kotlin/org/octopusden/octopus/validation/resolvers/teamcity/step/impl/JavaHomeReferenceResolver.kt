package org.octopusden.octopus.validation.resolvers.teamcity.step.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * For a [BuildStep], collects the set of `%paramName%` references that sit behind the step's
 * Java-home source — i.e. everything the step's Java version was ultimately derived from,
 * *including references that don't resolve to a defined parameter* (so a `%env.JAVA_HOME%` that is
 * an agent env var, not a build-config parameter, is still reported).
 *
 * This is the counterpart to [GradleBuildStepToolVersionResolver] / [MavenBuildStepToolVersionResolver]
 * / [CommandLineBuildStepToolVersionResolver]: those resolve the java-home value to a version; this
 * reports *which references were traversed* to get there, so a validator can ask "did the java-home
 * ever go through `%env.JAVA_HOME%`?". Per step type:
 *  - `GRADLE` / `MAVEN`: references behind the `target.jdk.home` parameter.
 *  - `COMMAND_LINE`: references behind each `script.content` token that itself resolves to a Java
 *    version (a command line has no dedicated java-home parameter, so the java-ish tokens are it).
 *  - `OTHER`: none.
 *
 * The parameter names mirror the tool-version resolvers (kept in sync deliberately — same TeamCity
 * runner parameters).
 */
class JavaHomeReferenceResolver(
    private val javaVersionResolver: ValueVersionResolver<JavaVersion>,
) {
    /** All references behind this step's java-home source(s); empty if the step exposes none. */
    fun javaHomeReferences(step: BuildStep): Set<String> =
        when (step.type) {
            StepType.GRADLE, StepType.MAVEN ->
                ParameterReferenceResolver.collectReferencedParameters(step.parameters, JDK_HOME_PARAMETER)
            StepType.COMMAND_LINE -> commandLineJavaHomeReferences(step)
            StepType.OTHER -> emptySet()
        }

    private fun commandLineJavaHomeReferences(step: BuildStep): Set<String> {
        val rawScript = step.parameters[SCRIPT_CONTENT_PARAMETER] ?: return emptySet()
        return rawScript
            .split(WHITESPACE)
            .filter { it.isNotBlank() }
            .filter { token ->
                javaVersionResolver.resolve(
                    ParameterReferenceResolver.resolveValue(step.parameters, token) ?: token,
                ) != null
            }.flatMap { token -> ParameterReferenceResolver.collectReferencedParametersInValue(step.parameters, token) }
            .toSet()
    }

    private companion object {
        const val JDK_HOME_PARAMETER = "target.jdk.home"
        const val SCRIPT_CONTENT_PARAMETER = "script.content"
        val WHITESPACE = Regex("""\s+""")
    }
}
