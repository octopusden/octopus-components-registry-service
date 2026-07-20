package org.octopusden.octopus.validation.teamcity.validator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.resolvers.teamcity.configuration.impl.DefaultBuildConfigurationResolver
import org.octopusden.octopus.validation.teamcity.GRADLE_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.MAVEN_TEMPLATE_ID
import org.octopusden.octopus.validation.teamcity.TestTemplateCatalog
import org.octopusden.octopus.validation.teamcity.buildConfig
import org.octopusden.octopus.validation.teamcity.tcProject
import org.octopusden.octopus.validation.validators.teamcity.AttachedToBuildTemplateValidator
import kotlin.test.assertEquals

class AttachedToBuildTemplateValidatorTest {
    private val validator = AttachedToBuildTemplateValidator(DefaultBuildConfigurationResolver(TestTemplateCatalog))

    @Test
    @DisplayName("SYS-064: OK when exactly one config is attached to a build template")
    fun `SYS-064 OK for a single attached config`() {
        val project = tcProject(configs = listOf(buildConfig("Gradle", templateIds = setOf(GRADLE_TEMPLATE_ID))))

        assertEquals(Status.OK, validator.validate(project).status)
    }

    @Test
    @DisplayName("SYS-064: WARNING when more than one config is attached to a build template")
    fun `SYS-064 WARNING for multiple attached configs`() {
        val project = tcProject(
            configs = listOf(
                buildConfig("Gradle", templateIds = setOf(GRADLE_TEMPLATE_ID)),
                buildConfig("Maven", templateIds = setOf(MAVEN_TEMPLATE_ID)),
            ),
        )

        assertEquals(Status.WARNING, validator.validate(project).status)
    }

    @Test
    @DisplayName("SYS-064: WARNING when no config is attached to a build template")
    fun `SYS-064 WARNING when nothing attached`() {
        val project = tcProject(configs = listOf(buildConfig("Plain")))

        assertEquals(Status.WARNING, validator.validate(project).status)
    }

    @Test
    @DisplayName("SYS-064: WARNING when the project has no build configurations at all")
    fun `SYS-064 WARNING for empty project`() {
        assertEquals(Status.WARNING, validator.validate(tcProject()).status)
    }
}
