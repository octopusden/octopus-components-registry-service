package org.octopusden.octopus.validation.resolvers.teamcity.step.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.ToolVersion
import org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/** A TeamCity `Maven2` runner step: `maven.path` gives the Maven version, `target.jdk.home` the Java version. */
class MavenBuildStepToolVersionResolver(
    private val javaVersionResolver: ValueVersionResolver<JavaVersion>,
    private val mavenVersionResolver: ValueVersionResolver<MavenVersion>,
) : BuildStepToolVersionResolver {
    override fun resolve(step: BuildStep): Set<ToolVersion> {
        val versions = mutableSetOf<ToolVersion>()
        ParameterReferenceResolver
            .resolveParameter(step.parameters, MAVEN_PATH_PARAMETER)
            ?.let(mavenVersionResolver::resolve)
            ?.let(versions::add)
        ParameterReferenceResolver
            .resolveParameter(step.parameters, JDK_HOME_PARAMETER)
            ?.let(javaVersionResolver::resolve)
            ?.let(versions::add)
        return versions
    }

    override fun supports(type: StepType): Boolean = type == StepType.MAVEN

    private companion object {
        const val MAVEN_PATH_PARAMETER = "maven.path"
        const val JDK_HOME_PARAMETER = "target.jdk.home"
    }
}
