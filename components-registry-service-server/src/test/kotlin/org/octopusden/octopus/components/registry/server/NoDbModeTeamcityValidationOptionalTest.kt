package org.octopusden.octopus.components.registry.server

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.octopusden.octopus.components.registry.server.teamcity.TeamcityValidationProperties
import org.octopusden.octopus.components.registry.server.teamcity.validation.TeamcityValidationService
import org.octopusden.octopus.components.registry.test.BaseComponentsRegistryServiceTest
import org.octopusden.octopus.validation.dto.teamcity.TemplateCatalog
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * TeamCity validation is a DB-backed feature, so a pure Git/`no-db` deployment must boot with
 * NO `teamcity.validation.*` configuration at all — even though those properties are mandatory
 * (`@NotBlank`/`@NotEmpty`) when the feature IS enabled.
 */
@ExtendWith(SpringExtension::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [ComponentRegistryServiceApplication::class],
    properties = [
        "auth-server.disabled=true",
        // Blank out the fixtures the `common` profile provides — mirror the inert application.yml baseline.
        "teamcity.validation.gradle-build-template-id=",
        "teamcity.validation.maven-build-template-id=",
        "teamcity.validation.release-family-template-ids=",
        "teamcity.validation.gradle-default-build-step-id=",
        "teamcity.validation.maven-default-build-step-id=",
    ],
)
@ActiveProfiles("common", "no-db")
class NoDbModeTeamcityValidationOptionalTest {
    companion object {
        init {
            BaseComponentsRegistryServiceTest.configureSpringAppTestDataDir()
        }
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    @DisplayName("no-db mode boots with blank teamcity.validation.* and the validation feature absent")
    fun `no-db boots without teamcity validation config`() {
        // Context started (autowiring succeeded) despite blank mandatory validation config.
        Assertions.assertTrue(
            ctx.getBeanNamesForType(TeamcityValidationProperties::class.java).isEmpty(),
            "TeamcityValidationProperties must NOT be registered in no-db mode (else its @NotBlank/@NotEmpty " +
                "would demand config for an unavailable feature)",
        )
        Assertions.assertTrue(
            ctx.getBeanNamesForType(TeamcityValidationService::class.java).isEmpty(),
            "the DB-backed TeamcityValidationService must be absent in no-db mode",
        )
        Assertions.assertTrue(
            ctx.getBeanNamesForType(TemplateCatalog::class.java).isEmpty(),
            "ConfigTemplateCatalog (injects TeamcityValidationProperties) must be absent in no-db mode",
        )
    }
}
