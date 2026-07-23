package org.octopusden.octopus.components.registry.server.teamcity

import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Bean Validation contract for `teamcity.validation.*` (see [TeamcityValidationProperties]):
 * configuration is mandatory — all five template/step-id fields must be non-blank/non-empty, so a
 * missing or blank value fails application startup rather than letting the suite run against an
 * empty catalog and emit invalid findings.
 */
class TeamcityValidationPropertiesTest {
    private fun props(
        gradleTemplate: String = "CDGradleBuild",
        mavenTemplate: String = "CDJavaMavenBuild",
        releaseFamily: Set<String> = setOf("CDRelease"),
        gradleStep: String = "GRADLE_ID",
        mavenStep: String = "MAVEN_ID",
    ) = TeamcityValidationProperties(
        gradleBuildTemplateId = gradleTemplate,
        mavenBuildTemplateId = mavenTemplate,
        releaseFamilyTemplateIds = releaseFamily,
        gradleDefaultBuildStepId = gradleStep,
        mavenDefaultBuildStepId = mavenStep,
    )

    @Test
    @DisplayName("a fully-configured set of values is valid")
    fun `fully configured passes`() {
        assertEquals(0, validator.validate(props()).size)
    }

    @Test
    @DisplayName("a blank gradle build template id is rejected")
    fun `blank gradle template fails`() {
        val violations = validator.validate(props(gradleTemplate = ""))
        assertEquals(1, violations.size)
        assertEquals("gradleBuildTemplateId", violations.first().propertyPath.toString())
    }

    @Test
    @DisplayName("a blank maven default build step id is rejected")
    fun `blank maven step fails`() {
        val violations = validator.validate(props(mavenStep = "  "))
        assertEquals(1, violations.size)
        assertEquals("mavenDefaultBuildStepId", violations.first().propertyPath.toString())
    }

    @Test
    @DisplayName("an empty release-family set is rejected")
    fun `empty release family fails`() {
        val violations = validator.validate(props(releaseFamily = emptySet()))
        assertEquals(1, violations.size)
        assertEquals("releaseFamilyTemplateIds", violations.first().propertyPath.toString())
    }

    @Test
    @DisplayName("a blank release-family element is rejected (Set<@NotBlank String>)")
    fun `blank release family element fails`() {
        val violations = validator.validate(props(releaseFamily = setOf("CDRelease", "")))
        assertEquals(1, violations.size)
        assertTrue(
            violations
                .first()
                .propertyPath
                .toString()
                .startsWith("releaseFamilyTemplateIds"),
        )
    }

    @Test
    @DisplayName("a whitespace-only release-family element is rejected (Set<@NotBlank String>)")
    fun `whitespace release family element fails`() {
        val violations = validator.validate(props(releaseFamily = setOf("CDRelease", "   ")))
        assertEquals(1, violations.size)
        assertTrue(
            violations
                .first()
                .propertyPath
                .toString()
                .startsWith("releaseFamilyTemplateIds"),
        )
    }

    @Test
    @DisplayName("the all-blank baseline is invalid (every required field violates)")
    fun `all blank fails on every required field`() {
        val violations =
            validator.validate(
                props(gradleTemplate = "", mavenTemplate = "", releaseFamily = emptySet(), gradleStep = "", mavenStep = ""),
            )
        // 4 @NotBlank strings + 1 @NotEmpty set.
        assertEquals(5, violations.size)
        assertTrue(
            violations.map { it.propertyPath.toString() }.toSet().containsAll(
                setOf(
                    "gradleBuildTemplateId",
                    "mavenBuildTemplateId",
                    "releaseFamilyTemplateIds",
                    "gradleDefaultBuildStepId",
                    "mavenDefaultBuildStepId",
                ),
            ),
        )
    }

    companion object {
        private lateinit var factory: ValidatorFactory
        private lateinit var validator: Validator

        @JvmStatic
        @BeforeAll
        fun setUp() {
            factory = Validation.buildDefaultValidatorFactory()
            validator = factory.validator
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            factory.close()
        }
    }
}
