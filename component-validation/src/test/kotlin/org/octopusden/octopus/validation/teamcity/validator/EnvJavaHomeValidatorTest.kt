package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepToolVersionResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.JavaHomeReferenceResolver
import org.octopusden.octopus.validation.resolvers.teamcity.value.impl.JavaVersionResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.params
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.EnvJavaHomeValidator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvJavaHomeValidatorTest {
    private val configs = DefaultBuildConfigurationResolver(TestTemplateCatalog)
    private val steps = DefaultBuildStepResolver(TestTemplateCatalog)
    private val validator =
        EnvJavaHomeValidator(
            configs,
            steps,
            DefaultBuildStepToolVersionResolver.standard(),
            JavaHomeReferenceResolver(JavaVersionResolver()),
        )

    @Test
    @DisplayName("NOT_APPLICABLE when there is nothing Java to inspect")
    fun `NOT_APPLICABLE when nothing to inspect`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.OTHER)))

        assertEquals(Status.NOT_APPLICABLE, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("WARNING when a step resolves Java from a jdk-home that does NOT reference %env.JAVA_HOME%")
    fun `WARNING when java home is a direct jdk reference`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    // Points straight at a specific JDK — never goes through %env.JAVA_HOME%.
                    parameters = params("target.jdk.home" to "%env.JDK_17%", "env.JDK_17" to "/usr/lib/jvm/java-17-openjdk"),
                ),
            ),
        )

        val result = validator.validate(tcProject(configs = listOf(config)))
        assertEquals(Status.WARNING, result.status)
        assertTrue(result.message!!.contains("$GRADLE_DEFAULT_STEP_ID in Gradle"))
    }

    @Test
    @DisplayName("OK when the jdk-home resolves through %env.JAVA_HOME%")
    fun `OK when java home is env JAVA_HOME`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    parameters = params(
                        "target.jdk.home" to "%env.JAVA_HOME%",
                        "env.JAVA_HOME" to "/usr/lib/jvm/java-21-openjdk",
                    ),
                ),
            ),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("OK when the jdk-home reaches %env.JAVA_HOME% through an intermediate parameter")
    fun `OK when java home reaches env JAVA_HOME indirectly`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    parameters = params(
                        "target.jdk.home" to "%custom.jdk%",
                        "custom.jdk" to "%env.JAVA_HOME%",
                        "env.JAVA_HOME" to "/usr/lib/jvm/java-17-openjdk",
                    ),
                ),
            ),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("WARNING for a command-line step whose java token does not reference %env.JAVA_HOME%")
    fun `WARNING for command line java token not from env`() {
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.COMMAND_LINE,
                    inherited = false,
                    parameters = params("script.content" to "env.JDK_17 build"),
                ),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("a step that resolves no Java version is not evaluated (no false WARNING)")
    fun `OK ignores steps with no resolvable java`() {
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

        assertEquals(Status.NOT_APPLICABLE, validator.validate(tcProject(configs = listOf(config))).status)
    }
}
