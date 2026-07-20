package org.octopusden.octopus.validation.resolvers.teamcity.step.impl

import org.octopusden.octopus.validation.dto.teamcity.BuildStep
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.dto.teamcity.ToolVersion
import org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.BuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.ValueVersionResolver

/**
 * A TeamCity `simpleRunner` (command-line) step — and, per an unconfirmed assumption (see
 * TD-016), an `IN_CONTAINER` step: reads `script.content`, splits it on whitespace, resolves
 * each token's `%param%` reference chain, and tries both the Maven and the Java resolver against
 * each resolved token (a plain command line gives no other signal about which tool a token
 * belongs to).
 */
class CommandLineBuildStepToolVersionResolver(
    private val javaVersionResolver: ValueVersionResolver<JavaVersion>,
    private val mavenVersionResolver: ValueVersionResolver<MavenVersion>,
) : BuildStepToolVersionResolver {
    override fun resolve(step: BuildStep): Set<ToolVersion> {
        val rawScript = step.parameters[SCRIPT_CONTENT_PARAMETER] ?: return emptySet()
        val versions = mutableSetOf<ToolVersion>()
        rawScript.split(WHITESPACE).filter { it.isNotBlank() }.forEach { token ->
            val resolved = ParameterReferenceResolver.resolveValue(step.parameters, token) ?: token
            javaVersionResolver.resolve(resolved)?.let(versions::add)
            mavenVersionResolver.resolve(resolved)?.let(versions::add)
        }
        return versions
    }

    override fun supports(type: StepType): Boolean = type == StepType.COMMAND_LINE || type == StepType.IN_CONTAINER

    private companion object {
        const val SCRIPT_CONTENT_PARAMETER = "script.content"
        val WHITESPACE = Regex("""\s+""")
    }
}
