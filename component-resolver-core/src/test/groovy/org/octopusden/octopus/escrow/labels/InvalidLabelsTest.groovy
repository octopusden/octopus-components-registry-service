package org.octopusden.octopus.escrow.labels

import groovy.transform.TypeChecked
import org.junit.jupiter.api.Test
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException

import java.nio.file.Paths

import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES

@TypeChecked
class InvalidLabelsTest {

    @Test
    void testEEComponentWithoutCopyrightThrowsExceptionCauseOfCopyrightAbsent() {
        def basePath = Paths.get(
                InvalidLabelsTest.class.getResource(
                        "/labels/invalid/Aggregator.groovy"
                ).toURI()
        )
        def componentRegistryInfo = ComponentRegistryInfo.createFromFileSystem(
                basePath.getParent().toString(),
                basePath.getFileName().toString()
        )

        try {
            new EscrowConfigurationLoader(
                    new ConfigLoader(
                            componentRegistryInfo,
                            VERSION_NAMES,
                            PRODUCT_TYPES
                    ),
                    SUPPORTED_GROUP_IDS,
                    SUPPORTED_SYSTEMS,
                    VERSION_NAMES
            ).loadFullConfiguration(Collections.emptyMap())
            assert false: "Test should fail due to 'labels' field presents in Defaults component"
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("Unknown attribute 'labels' in Defaults section of escrow config file")
        }
    }
}
