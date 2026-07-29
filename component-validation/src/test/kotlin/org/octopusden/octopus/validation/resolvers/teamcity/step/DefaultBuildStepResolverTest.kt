package org.octopusden.octopus.validation.resolvers.teamcity.step

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultBuildStepResolverTest {
    private val resolver = DefaultBuildStepResolver(TestTemplateCatalog)

    @Test
    @DisplayName("defaultBuildStep finds the template's default step by id")
    fun `defaultBuildStep finds the configured id`() {
        val defaultStep = buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE)
        val config = buildConfig("Gradle", templateIds = setOf(GRADLE_TEMPLATE_ID), steps = listOf(defaultStep))

        assertEquals(defaultStep, resolver.defaultBuildStep(config))
    }

    @Test
    @DisplayName("defaultBuildStep is null when the config isn't attached to a build template")
    fun `defaultBuildStep null for non-template config`() {
        val config = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.COMMAND_LINE)))

        assertNull(resolver.defaultBuildStep(config))
    }

    @Test
    @DisplayName("stepsOfTypes filters to the requested runner types")
    fun `stepsOfTypes filters by type`() {
        val gradleStep = buildStep("s1", StepType.GRADLE)
        val mavenStep = buildStep("s2", StepType.MAVEN)
        val cmdStep = buildStep("s3", StepType.COMMAND_LINE)
        val config = buildConfig("Plain", steps = listOf(gradleStep, mavenStep, cmdStep))

        val result = resolver.stepsOfTypes(config, setOf(StepType.MAVEN, StepType.COMMAND_LINE))

        assertEquals(setOf("s2", "s3"), result.map { it.id }.toSet())
    }
}
