package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.relevantBuildSteps
import kotlin.test.assertEquals

class RelevantBuildStepsTest {
    private val configs = DefaultBuildConfigurationResolver(TestTemplateCatalog)
    private val steps = DefaultBuildStepResolver(TestTemplateCatalog)

    @Test
    @DisplayName("includes an uninherited step from a non-template config")
    fun `includes uninherited step from plain config`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.GRADLE, inherited = false)))

        val result = relevantBuildSteps(tcProject(configs = listOf(plainConfig)), configs, steps)

        assertEquals(listOf("s1"), result.map { it.step.id })
    }

    @Test
    @DisplayName("excludes an inherited step from a non-template config")
    fun `excludes inherited step from plain config`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.GRADLE, inherited = true)))

        val result = relevantBuildSteps(tcProject(configs = listOf(plainConfig)), configs, steps)

        assertEquals(emptyList(), result)
    }

    @Test
    @DisplayName("includes an attached config's default step even when inherited")
    fun `includes inherited default step`() {
        val templateConfig = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE, inherited = true)),
        )

        val result = relevantBuildSteps(tcProject(configs = listOf(templateConfig)), configs, steps)

        assertEquals(listOf(GRADLE_DEFAULT_STEP_ID), result.map { it.step.id })
    }

    @Test
    @DisplayName("does not duplicate an attached config's overridden default step")
    fun `dedupes overridden default step`() {
        val templateConfig = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE, inherited = false)),
        )

        val result = relevantBuildSteps(tcProject(configs = listOf(templateConfig)), configs, steps)

        // Would appear once via "uninherited" and once via "attached default step" if not de-duplicated.
        assertEquals(listOf(GRADLE_DEFAULT_STEP_ID), result.map { it.step.id })
    }

    @Test
    @DisplayName("also reports which build configuration each step came from")
    fun `pairs each step with its build configuration`() {
        val templateConfig = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE, inherited = true)),
        )

        val result = relevantBuildSteps(tcProject(configs = listOf(templateConfig)), configs, steps)

        assertEquals(listOf("Gradle"), result.map { it.configuration.id })
    }

    @Test
    @DisplayName("returns nothing for a project with no build configurations")
    fun `empty project yields no steps`() {
        assertEquals(emptyList(), relevantBuildSteps(tcProject(), configs, steps))
    }
}
