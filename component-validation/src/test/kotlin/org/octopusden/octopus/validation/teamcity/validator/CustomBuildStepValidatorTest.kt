package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepToolVersionResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.MAVEN_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.params
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.CustomBuildStepValidator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomBuildStepValidatorTest {
    private val configs = DefaultBuildConfigurationResolver(TestTemplateCatalog)
    private val toolVersionResolver = DefaultBuildStepToolVersionResolver.standard()
    private val validator = CustomBuildStepValidator(configs, toolVersionResolver)

    @Test
    @DisplayName("SYS-077: OK when there is no uninherited step at all")
    fun `SYS-077 OK when nothing custom`() {
        val templateConfig = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE, inherited = true)),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(templateConfig))).status)
    }

    @Test
    @DisplayName("SYS-077: OK when an uninherited step exists but resolves no tool version")
    fun `SYS-077 OK when custom step resolves nothing`() {
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(buildStep("s1", StepType.OTHER, inherited = false)),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("SYS-077: WARNING when an uninherited step in a non-template config resolves a Java version")
    fun `SYS-077 WARNING for non-template custom step with a java version`() {
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.GRADLE,
                    inherited = false,
                    parameters = params("target.jdk.home" to "env.JDK_21"),
                ),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("SYS-077: WARNING when an uninherited step resolves a Maven version (both tools count)")
    fun `SYS-077 WARNING for non-template custom step with a maven version`() {
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.MAVEN,
                    inherited = false,
                    parameters = params("maven.path" to "env.MAVEN_3.6.3"),
                ),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("SYS-077: WARNING when an attached config's overridden default step resolves a version")
    fun `SYS-077 WARNING for overridden default step`() {
        val templateConfig = buildConfig(
            "Maven",
            templateIds = setOf(MAVEN_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    "RUNNER_MAVEN_DEFAULT",
                    StepType.MAVEN,
                    inherited = false,
                    parameters = params("maven.path" to "env.MAVEN_LATEST"),
                ),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(templateConfig))).status)
    }

    @Test
    @DisplayName("the message reports the flagged step and its build configuration, not the tool version")
    fun `message includes step and configuration`() {
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.GRADLE,
                    inherited = false,
                    parameters = params("target.jdk.home" to "env.JDK_21"),
                ),
            ),
        )

        val result = validator.validate(tcProject(configs = listOf(plainConfig)))

        assertEquals(Status.WARNING, result.status)
        assertTrue(result.message!!.contains("s1 in Plain"))
    }
}
