package org.octopusden.octopus.validation.resolvers.teamcity.step.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.ToolVersion
import org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/** A TeamCity `gradle-runner` step: `target.jdk.home` gives the Java version. No Maven version — Gradle steps don't use Maven. */
class GradleBuildStepToolVersionResolver(
    private val javaVersionResolver: ValueVersionResolver<JavaVersion>,
) : BuildStepToolVersionResolver {
    override fun resolve(step: BuildStep): Set<ToolVersion> =
        ParameterReferenceResolver
            .resolveParameter(step.parameters, JDK_HOME_PARAMETER)
            ?.let(javaVersionResolver::resolve)
            ?.let(::setOf)
            ?: emptySet()

    override fun supports(type: StepType): Boolean = type == StepType.GRADLE

    private companion object {
        const val JDK_HOME_PARAMETER = "target.jdk.home"
    }
}
