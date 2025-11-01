package org.octopusden.octopus.escrow.copyright

import groovy.transform.TypeChecked
import org.junit.jupiter.api.Test
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException

import java.nio.file.Paths

import static org.octopusden.octopus.escrow.TestConfigUtils.*

@TypeChecked
class NonDefaultCopyrightTest {

    @Test
    void testEEComponentWithoutCopyrightThrowsExceptionCauseOfCopyrightAbsent() {
        def basePath = Paths.get(
                DefaultCopyrightTest.class.getResource("/copyright-configs/non-default-copyright/Aggregator.groovy").toURI()
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
            assert false: "test should fail due to copyright absent in ee component"
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("copyright is not set in 'ee_component_without_copyright'")
        }
    }
}
