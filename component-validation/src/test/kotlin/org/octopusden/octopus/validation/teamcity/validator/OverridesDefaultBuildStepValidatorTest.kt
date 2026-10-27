package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.resolvers.teamcity.step.impl.DefaultBuildStepResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_DEFAULT_STEP_ID
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.buildStep
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.OverridesDefaultBuildStepValidator
import kotlin.test.assertEquals

class OverridesDefaultBuildStepValidatorTest {
    private val validator = OverridesDefaultBuildStepValidator(
        DefaultBuildConfigurationResolver(TestTemplateCatalog),
        DefaultBuildStepResolver(TestTemplateCatalog),
    )

    @Test
    @DisplayName("SYS-076: NOT_APPLICABLE when no config is attached to a build template")
    fun `SYS-076 NOT_APPLICABLE when nothing attached`() {
        val project = tcProject(configs = listOf(buildConfig("Plain")))

        assertEquals(Status.NOT_APPLICABLE, validator.validate(project).status)
    }

    @Test
    @DisplayName("SYS-076: OK when the attached config's default step is inherited")
    fun `SYS-076 OK when default step inherited`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE, inherited = true)),
        )

        assertEquals(Status.OK, validator.validate(tcProject(configs = listOf(config))).status)
    }

    @Test
    @DisplayName("SYS-076: WARNING when the attached config's default step is overridden")
    fun `SYS-076 WARNING when default step overridden`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(buildStep(GRADLE_DEFAULT_STEP_ID, StepType.GRADLE, inherited = false)),
        )

        assertEquals(Status.WARNING, validator.validate(tcProject(configs = listOf(config))).status)
    }
}
