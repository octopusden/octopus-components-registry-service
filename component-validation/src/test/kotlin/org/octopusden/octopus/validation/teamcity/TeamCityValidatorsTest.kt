package org.octopusden.octopus.validation.teamcity

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.dto.teamcity.StepType
import org.octopusden.octopus.validation.validators.TeamCityValidators
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType
import kotlin.test.assertEquals

class TeamCityValidatorsTest {
    private val suite = TeamCityValidators(catalog = TestTemplateCatalog)

    @Test
    @DisplayName("SYS-081: the suite returns exactly the seven TeamCity results, in a well-behaved project")
    fun `SYS-081 well-behaved project resolves all seven checks`() {
        val config = buildConfig(
            "Gradle",
            templateIds = setOf(GRADLE_TEMPLATE_ID),
            steps = listOf(
                buildStep(
                    GRADLE_DEFAULT_STEP_ID,
                    StepType.GRADLE,
                    inherited = true,
                    // Java-home resolves to 21 AND goes through the standard %env.JAVA_HOME% reference,
                    // so JAVA_HOME_NOT_FROM_ENV stays OK alongside the version checks.
                    parameters = params(
                        "target.jdk.home" to "%env.JAVA_HOME%",
                        "env.JAVA_HOME" to "/usr/lib/jvm/java-21-openjdk",
                    ),
                ),
            ),
        )
        val project = tcProject(configs = listOf(config))

        val results = suite.validate(project)

        assertEquals(
            listOf(
                TeamCityValidationType.ATTACHED_TO_BUILD_TEMPLATE to Status.OK,
                TeamCityValidationType.OVERRIDES_DEFAULT_BUILD_STEP to Status.OK,
                TeamCityValidationType.HAS_CUSTOM_BUILD_STEP to Status.OK,
                TeamCityValidationType.USES_OLD_JAVA_VERSION to Status.OK,
                TeamCityValidationType.MULTIPLE_JAVA_VERSIONS to Status.OK,
                TeamCityValidationType.MULTIPLE_MAVEN_VERSIONS to Status.OK,
                TeamCityValidationType.JAVA_HOME_NOT_FROM_ENV to Status.OK,
            ),
            results.map { (it.type as TeamCityValidationType) to it.status },
        )
    }

    @Test
    @DisplayName("SYS-081: an unattached project with an uninherited old-Java step flags the relevant checks")
    fun `SYS-081 unattached project with custom java 8 step`() {
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
        val project = tcProject(configs = listOf(plainConfig))

        val results = suite.validate(project).associateBy { it.type }

        assertEquals(Status.WARNING, results[TeamCityValidationType.ATTACHED_TO_BUILD_TEMPLATE]?.status)
        assertEquals(Status.NOT_APPLICABLE, results[TeamCityValidationType.OVERRIDES_DEFAULT_BUILD_STEP]?.status)
        assertEquals(Status.WARNING, results[TeamCityValidationType.HAS_CUSTOM_BUILD_STEP]?.status)
        assertEquals(Status.WARNING, results[TeamCityValidationType.USES_OLD_JAVA_VERSION]?.status)
        assertEquals(Status.OK, results[TeamCityValidationType.MULTIPLE_JAVA_VERSIONS]?.status)
        assertEquals(Status.OK, results[TeamCityValidationType.MULTIPLE_MAVEN_VERSIONS]?.status)
        assertEquals(Status.WARNING, results[TeamCityValidationType.JAVA_HOME_NOT_FROM_ENV]?.status)
    }
}
