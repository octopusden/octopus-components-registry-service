package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.MavenVersion
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepToolVersionResolver
import org.octopusden.octopus.validation.teamcity.MAVEN_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.MAVEN_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.params
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.MultipleVersionValidator
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultipleMavenVersionValidatorTest {
    private val configs = DefaultBuildConfigurationResolver(TestTemplateCatalog)
    private val steps = DefaultBuildStepResolver(TestTemplateCatalog)
    private val validator = MultipleVersionValidator(
        TeamCityValidationType.MULTIPLE_MAVEN_VERSIONS,
        configs,
        steps,
        DefaultBuildStepToolVersionResolver.standard(),
        "Maven",
    ) { it is MavenVersion }

    @Test
    @DisplayName("NOT_APPLICABLE when there is nothing Maven to inspect")
    fun `NOT_APPLICABLE when nothing to inspect`() {
        val plainConfig = buildConfig("Plain", steps = listOf(buildStep("s1", StepType.OTHER)))

        assertEquals(Status.NOT_APPLICABLE, validator.validate(tcProject(configs = listOf(plainConfig))).status)
    }

    @Test
    @DisplayName("OK when only one distinct Maven version resolves")
    fun `OK for a single maven version`() {
        val config = buildConfig(
            "Maven",
            templateIds = setOf(MAVEN_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    MAVEN_DEFAULT_STEP_ID,
                    StepType.MAVEN,
                    inherited = true,
                    parameters = params("maven.path" to "env.MAVEN_3.6.3"),
                ),
            ),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("WARNING when more than one distinct Maven version resolves across steps")
    fun `WARNING for multiple maven versions`() {
        val templateConfig = buildConfig(
            "Maven",
            templateIds = setOf(MAVEN_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    MAVEN_DEFAULT_STEP_ID,
                    StepType.MAVEN,
                    inherited = true,
                    parameters = params("maven.path" to "env.MAVEN_3.6.3"),
                ),
            ),
        )
        val plainConfig = buildConfig(
            "Plain",
            steps = listOf(
                buildStep(
                    "s1",
                    StepType.MAVEN,
                    inherited = false,
                    parameters = params("maven.path" to "env.MAVEN_3.3.9"),
                ),
            ),
        )

        val result = validator.validate(tcProject(configs = listOf(templateConfig, plainConfig)))

        assertEquals(Status.WARNING, result.status)
        assertTrue(result.message!!.contains("3.6.3 ($MAVEN_DEFAULT_STEP_ID ($MAVEN_DEFAULT_STEP_ID) in Maven)"))
        assertTrue(result.message!!.contains("3.3.9 (s1 (s1) in Plain)"))
    }
}
