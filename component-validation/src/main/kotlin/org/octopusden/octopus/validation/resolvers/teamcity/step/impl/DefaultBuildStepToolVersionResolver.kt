package org.octopusden.octopus.validation.resolvers.teamcity.step.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.ToolVersion
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.JavaVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.MavenVersionResolver

/**
 * The standard per-[StepType] dispatch: `MAVEN` -> [MavenBuildStepToolVersionResolver], `GRADLE`
 * -> [GradleBuildStepToolVersionResolver], `COMMAND_LINE` -> [CommandLineBuildStepToolVersionResolver].
 */
class DefaultBuildStepToolVersionResolver(
    private val resolvers: Map<StepType, BuildStepToolVersionResolver>,
) : BuildStepToolVersionResolver {
    override fun resolve(step: BuildStep): Set<ToolVersion> = resolvers[step.type]?.resolve(step) ?: emptySet()

    override fun supports(type: StepType): Boolean = type in resolvers

    companion object {
        fun standard(
            javaVersionResolver: ValueVersionResolver<JavaVersion> = JavaVersionResolver(),
            mavenVersionResolver: ValueVersionResolver<MavenVersion> = MavenVersionResolver(),
        ): DefaultBuildStepToolVersionResolver {
            val commandLine = CommandLineBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)
            val maven = MavenBuildStepToolVersionResolver(javaVersionResolver, mavenVersionResolver)
            val gradle = GradleBuildStepToolVersionResolver(javaVersionResolver)
            return DefaultBuildStepToolVersionResolver(
                mapOf(
                    StepType.MAVEN to maven,
                    StepType.GRADLE to gradle,
                    StepType.COMMAND_LINE to commandLine,
                ),
            )
        }
    }
}
