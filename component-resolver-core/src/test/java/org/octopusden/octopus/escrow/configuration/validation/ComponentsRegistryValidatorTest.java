package org.octopusden.octopus.escrow.configuration.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.octopusden.octopus.escrow.TestConfigUtils.*;

@DisplayName("Components registry component's validator test")
class ComponentsRegistryValidatorTest {

    private static Stream<Arguments> crsData() {
        return Stream.of(
                Arguments.of("CREG-153", Arrays.asList("groupId:artifactId patterns of module octopusstreams has intersection with octopusstreams-commons")),
                Arguments.of("CREG-182", Arrays.asList("Archived component 'component-integration' can't be explicitly distributed. Pls set distribution->explicit=false"))
        );
    }

    /**
     * Test validation of the components' configurations.
     * Validation is done on loading Components Registry.
     * Verify bad configured Components Registries for expected validation errors.
     */
    @DisplayName("Test Component Registry validation")
    @ParameterizedTest(name = "For {0} expects {1} validation error(s)")
    @MethodSource("crsData")
    void testCrsValidation(
            final String sourceComponentRegistry,
            final Collection<String> expectedValidationErrors
    ) throws Exception {
        final Path aggregatorPath = Paths.get(Objects.requireNonNull(ComponentsRegistryValidatorTest.class.getResource("/" + sourceComponentRegistry + "/Aggregator.groovy")).toURI());
        final EscrowConfigurationLoader escrowConfigurationLoader = new EscrowConfigurationLoader(
                new ConfigLoader(
                        ComponentRegistryInfo.createFromFileSystem(aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString()
                        ),
                        VERSION_NAMES,
                        PRODUCT_TYPES
                ), SUPPORTED_GROUP_IDS, SYSTEM_MANDATORY, SUPPORTED_SYSTEMS, VERSION_NAMES);
        assertThatThrownBy(() -> escrowConfigurationLoader.loadFullConfiguration(Collections.emptyMap()))
                .isInstanceOf(EscrowConfigurationException.class).hasMessageContainingAll(expectedValidationErrors.toArray(new String[0]));
    }
}
