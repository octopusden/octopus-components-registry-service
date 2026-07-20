package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.JavaVersion
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
import org.octopusden.octopus.validation.validators.teamcity.MultipleVersionValidator
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultipleJavaVersionValidatorTest {
    private val configs = DefaultBuildConfigurationResolver(TestTemplateCatalog)
    private val steps = DefaultBuildStepResolver(TestTemplateCatalog)
    private val validator = MultipleVersionValidator(
        TeamCityValidationType.MULTIPLE_JAVA_VERSIONS,
        configs,
        steps,
        DefaultBuildStepToolVersionResolver.standard(),
        "Java",
    ) { it is JavaVersion }

    @Test
    @DisplayName("NOT_APPLICABLE when there is nothing Java to inspect")
    fun `NOT_APPLICABLE when nothing to inspect`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.OTHER)))

        assertEquals(Status.NOT_APPLICABLE, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("OK when only one distinct Java version resolves")
    fun `OK for a single java version`() {
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
    @DisplayName("WARNING when more than one distinct Java version resolves across steps")
    fun `WARNING for multiple java versions`() {
        val templateConfig = buildConfig(
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
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.GRADLE,
                    inherited = false,
                    parameters = params("target.jdk.home" to "env.JDK_17"),
                ),
            ),
        )

        val result = validator.validate(tcProject(configs = listOf(templateConfig, plainConfig)))

        assertEquals(Status.WARNING, result.status)
        assertTrue(result.message!!.contains("21 ($GRADLE_DEFAULT_STEP_ID ($GRADLE_DEFAULT_STEP_ID) in Gradle)"))
        assertTrue(result.message!!.contains("17 (s1 (s1) in Plain)"))
    }
}
