package org.octopusden.octopus.escrow.configuration.validation

import org.junit.Test
import org.octopusden.octopus.escrow.BuildSystem
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.model.ValidationConfig
import org.octopusden.octopus.escrow.model.VCSSettings
import org.octopusden.releng.versions.VersionNames

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class EscrowConfigValidatorTest {

    private static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")

    private static EscrowConfigValidator newValidator() {
        new EscrowConfigValidator(
                ["org.octopusden"],
                ["CLASSIC"],
                VERSION_NAMES,
                null,
                new ValidationConfig()
        )
    }

    @Test
    void validateVcsSettingsRegistersErrorWhenVcsRootsEmptyAndBuildSystemRequiresThem() {
        def validator = newValidator()
        def moduleConfig = new EscrowModuleConfig(
                buildSystem: BuildSystem.MAVEN,
                vcsSettings: VCSSettings.createEmpty()
        )

        validator.validateVcsSettings(moduleConfig, "my-component")

        assertTrue(validator.errors.any {
            it.contains("No VCS roots is configured for component 'my-component'") &&
                    it.contains("type=MAVEN")
        })
    }

    @Test
    void validateVcsSettingsSkipsVcsRootsCheckForProvidedBuildSystem() {
        def validator = newValidator()
        def moduleConfig = new EscrowModuleConfig(
                buildSystem: BuildSystem.PROVIDED,
                vcsSettings: VCSSettings.createEmpty()
        )

        validator.validateVcsSettings(moduleConfig, "provided-component")

        assertFalse(validator.errors.any { it.contains("No VCS roots is configured") })
    }

    @Test
    void validateVcsSettingsSkipsVcsRootsCheckWhenVcsMarkedNotAvailable() {
        def validator = newValidator()
        def moduleConfig = new EscrowModuleConfig(
                buildSystem: BuildSystem.GRADLE,
                vcsSettings: VCSSettings.create("NOT_AVAILABLE")
        )

        validator.validateVcsSettings(moduleConfig, "no-vcs-component")

        assertFalse(validator.errors.any { it.contains("No VCS roots is configured") })
    }
}
