package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepToolVersionResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.params
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.OldJavaVersionValidator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OldJavaVersionValidatorTest {
    private val configs = DefaultBuildConfigurationResolver(TestTemplateCatalog)
    private val steps = DefaultBuildStepResolver(TestTemplateCatalog)
    private val validator = OldJavaVersionValidator(configs, steps, DefaultBuildStepToolVersionResolver.standard())

    @Test
    @DisplayName("SYS-078: NOT_APPLICABLE when there is nothing Java to inspect")
    fun `SYS-078 NOT_APPLICABLE when nothing to inspect`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.OTHER)))

        assertEquals(Status.NOT_APPLICABLE, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("SYS-078: WARNING when the untouched inherited default step resolves to Java 1.8")
    fun `SYS-078 WARNING for inherited default step on Java 8`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    parameters = params("target.jdk.home" to "env.JDK_1_8"),
                ),
            ),
        )

        val result = validator.validate(tcProject(configs = listOf(config)))
        assertEquals(Status.WARNING, result.status)
        assertTrue(result.message!!.contains("$GRADLE_DEFAULT_STEP_ID ($GRADLE_DEFAULT_STEP_ID) in Gradle"))
    }

    @Test
    @DisplayName("SYS-078: OK when the inherited default step resolves to a modern Java version")
    fun `SYS-078 OK for inherited default step on Java 21`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    parameters = params("target.jdk.home" to "env.JDK_21"),
                ),
            ),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("SYS-078: WARNING when an overridden default step resolves to Java 1.8")
    fun `SYS-078 WARNING for overridden default step on Java 8`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = false,
                    parameters = params("target.jdk.home" to "env.JDK_1_8"),
                ),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("SYS-078: WARNING when an uninherited custom step on a non-template config resolves to Java 1.8")
    fun `SYS-078 WARNING for custom step on Java 8`() {
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.COMMAND_LINE,
                    inherited = false,
                    parameters = params("script.content" to "env.JDK_1_8"),
                ),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("SYS-078: an unresolved version is ignored, not flagged (D7)")
    fun `SYS-078 OK when version cannot be resolved`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    parameters = params("target.jdk.home" to "not-a-version"),
                ),
            ),
        )

        // Nothing resolved, but a default step WAS inspected -> OK, not NOT_APPLICABLE.
        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }
}
