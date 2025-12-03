package org.octopusden.octopus.escrow.configuration.validation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.octopusden.octopus.escrow.TestConfigUtils.COPYRIGHT_PATH;
import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES;
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS;
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS;
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES;

@DisplayName("Components registry component's validator test")
class ComponentsRegistryValidatorTest {

    private static Stream<Arguments> crsInvalidData() {
        return Stream.of(
                Arguments.of("CREG-153", Arrays.asList("groupId:artifactId patterns of module octopusstreams has intersection with octopusstreams-commons")),
                Arguments.of("CREG-182", Arrays.asList("Archived component 'component-integration' can't be explicitly distributed. Pls set distribution->explicit=false")),
                Arguments.of("version-prefix-intersection", Arrays.asList("Following components have no version prefix in Jira project 'PROJECT_2': component4, component5",
                        "Following components have the same version prefix 'versionPrefix' in Jira project 'PROJECT': component1, component3")),
                Arguments.of("test-multi-release-managers/invalid", Arrays.asList(
                        "releaseManager is not matched '\\w+(,\\w+)*' in 'test-component-1'",
                        "releaseManager is not matched '\\w+(,\\w+)*' in 'test-component-2'",
                        "releaseManager is not set in 'test-component-3'"))
        );
    }

    private static Stream<String> crsValidData() {
        return Stream.of("test-multi-release-managers/valid");
    }

    /**
     * Test validation of the components' configurations.
     * Validation is done on loading Components Registry.
     * Verify bad configured Components Registries for expected validation errors.
     */
    @DisplayName("Test Component Registry validation for invalid data")
    @ParameterizedTest(name = "For {0} expects {1} validation error(s)")
    @MethodSource("crsInvalidData")
    void testCrsValidationFails(final String sourceComponentRegistry,
                                final Collection<String> expectedValidationErrors) throws Exception {
        EscrowConfigurationLoader loader = createLoader("/" + sourceComponentRegistry + "/Aggregator.groovy");
        assertThatThrownBy(() -> loader.loadFullConfiguration(emptyMap()))
                .isInstanceOf(EscrowConfigurationException.class)
                .hasMessageContainingAll(expectedValidationErrors.toArray(new String[0]));
    }

    /**
     * Verify correctly configured Components Registries do not produce validation errors.
     */
    @DisplayName("Test Component Registry validation for valid data")
    @ParameterizedTest(name = "For {0} expects no validation errors")
    @MethodSource("crsValidData")
    void testCrsValidationPasses(final String sourceComponentRegistry) throws Exception {
        EscrowConfigurationLoader loader = createLoader("/" + sourceComponentRegistry + "/Aggregator.groovy");
        assertThatCode(() -> loader.loadFullConfiguration(emptyMap())).doesNotThrowAnyException();
    }

    private static EscrowConfigurationLoader createLoader(String path) throws Exception {
        final Path aggregatorPath = Paths.get(Objects.requireNonNull(
                ComponentsRegistryValidatorTest.class.getResource(path)
        ).toURI());
        return new EscrowConfigurationLoader(
                new ConfigLoader(
                        ComponentRegistryInfo.createFromFileSystem(
                                aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString()
                        ),
                        VERSION_NAMES,
                        PRODUCT_TYPES
                ),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS,
                VERSION_NAMES,
                COPYRIGHT_PATH
        );
    }
}
