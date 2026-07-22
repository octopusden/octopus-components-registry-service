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
    @DisplayName("SYS-083 NOT_APPLICABLE when there is nothing Java to inspect")
    fun `SYS-083 NOT_APPLICABLE when nothing to inspect`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.OTHER)))

        assertEquals(Status.NOT_APPLICABLE, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("SYS-083 OK when no Java version resolves")
    fun `SYS-083 OK when no Java version resolves`() {
        // A GRADLE step is inspectable (buildStepToolVersionResolver.supports == true), but its
        // target.jdk.home value carries no jdk/java/jvm marker, so nothing resolves. Distinct from
        // NOT_APPLICABLE (StepType.OTHER, nothing inspectable at all).
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    parameters = params("target.jdk.home" to "BUILD_ENV_21"),
                ),
            ),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("SYS-083 OK for a single distinct Java version")
    fun `SYS-083 OK for a single distinct Java version`() {
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
    @DisplayName("SYS-083 WARNING for multiple distinct Java versions")
    fun `SYS-083 WARNING for multiple distinct Java versions`() {
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
        assertTrue(result.message!!.startsWith("Multiple Java versions found:\n"))
        assertTrue(result.message!!.contains("21:\n- $GRADLE_DEFAULT_STEP_ID ($GRADLE_DEFAULT_STEP_ID) in Gradle"))
        assertTrue(result.message!!.contains("17:\n- s1 (s1) in Plain"))
    }
}
