package org.octopusden.octopus.escrow.copyright

import groovy.transform.TypeChecked
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration

import java.nio.file.Paths

import static org.assertj.core.api.AssertionsForClassTypes.assertThat
import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES

@TypeChecked
class DefaultCopyrightTest {

    private static ConfigLoader configLoader
    private static EscrowConfiguration escrowConfiguration
    private static EscrowConfigurationLoader escrowConfigurationLoader

    private static final COMPANY_NAME1_COPYRIGHT_PATH = "copyrights/companyName1"
    private static final COMPANY_NAME2_COPYRIGHT_PATH = "copyrights/companyName2"
    private static final String DEFAULT_COMPONENT_NAME = "Defaults"
    private static final String COPYRIGHT_FIELD = "copyright"

    @BeforeAll
    static void init() {
        def basePath = Paths.get(
                DefaultCopyrightTest.class.getResource("/copyright-configs/default-copyright/Aggregator.groovy").toURI()
        )
        def componentRegistryInfo = ComponentRegistryInfo.createFromFileSystem(
                basePath.getParent().toString(),
                basePath.getFileName().toString()
        )

        configLoader = new ConfigLoader(
                componentRegistryInfo,
                VERSION_NAMES,
                PRODUCT_TYPES
        )
        escrowConfigurationLoader = new EscrowConfigurationLoader(
                configLoader,
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS,
                VERSION_NAMES
        )

        escrowConfiguration = escrowConfigurationLoader.loadFullConfiguration(Collections.emptyMap())
    }

    @Test
    void testDefaultComponentContainsCopyright() {
        def config = configLoader.loadModuleConfig()

        def defaultComponent = config.getProperty(DEFAULT_COMPONENT_NAME) as ConfigObject
        assert defaultComponent.containsKey(COPYRIGHT_FIELD)
        def defaultCopyrightValue = defaultComponent.getProperty(COPYRIGHT_FIELD) as String
        assert defaultCopyrightValue == COMPANY_NAME1_COPYRIGHT_PATH
    }

    @Test
    void testComponentWithoutCopyrightNotContainsCopyright() {
        def config = configLoader.loadModuleConfig()

        def componentWithCopyright = config.getProperty("component_without_copyright") as ConfigObject
        assert !componentWithCopyright.containsKey(COPYRIGHT_FIELD)
    }

    @Test
    void testComponentWithCopyrightContainsCopyright() {
        def config = configLoader.loadModuleConfig()

        def componentWithCopyright = config.getProperty("component_with_copyright") as ConfigObject
        assert componentWithCopyright.containsKey(COPYRIGHT_FIELD)
        def componentWithCopyrightValue = componentWithCopyright.getProperty(COPYRIGHT_FIELD) as String
        assert componentWithCopyrightValue == COMPANY_NAME2_COPYRIGHT_PATH
    }

    @Test
    void testEEComponentWithoutCopyrightNotContainsCopyright() {
        def config = configLoader.loadModuleConfig()

        def componentWithCopyright = config.getProperty("ee_component_without_copyright") as ConfigObject
        assert !componentWithCopyright.containsKey(COPYRIGHT_FIELD)
    }

    @Test
    void testComponentWithoutCopyrightInheritsCopyrightFromDefault() {
        def escrowModule = escrowConfiguration.escrowModules.get("component_without_copyright")
        def moduleConfig = escrowModule.getModuleConfigurations().first()

        assertThat(moduleConfig.copyright).isEqualTo(COMPANY_NAME1_COPYRIGHT_PATH)
    }

    @Test
    void testComponentWithCopyrightUsesOwnCopyright() {
        def escrowModule = escrowConfiguration.escrowModules.get("component_with_copyright")
        def moduleConfig = escrowModule.getModuleConfigurations().first()

        assertThat(moduleConfig.copyright).isEqualTo(COMPANY_NAME2_COPYRIGHT_PATH)
    }

    @Test
    void testEEComponentWithoutCopyrightInheritsCopyrightFromDefault() {
        def escrowModule = escrowConfiguration.escrowModules.get("ee_component_without_copyright")
        def moduleConfig = escrowModule.getModuleConfigurations().first()

        assertThat(moduleConfig.copyright).isEqualTo(COMPANY_NAME1_COPYRIGHT_PATH)
    }
}
