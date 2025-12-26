package org.octopusden.octopus.escrow.labels

import groovy.transform.TypeChecked
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import static org.assertj.core.api.AssertionsForClassTypes.assertThat


import java.nio.file.Paths

import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES

@TypeChecked
class ValidLabelsTest {

    private static ConfigLoader configLoader
    private static EscrowConfiguration escrowConfiguration
    private static EscrowConfigurationLoader escrowConfigurationLoader

    private static final Set<String> firstAvailableLabel = ['Label1'].toSet()
    private static final Set<String> availableLabels = firstAvailableLabel + ['Label3']
    private static final String LABELS_FIELD = "labels"


    @BeforeAll
    static void init() {
        def basePath = Paths.get(
                ValidLabelsTest.class.getResource(
                        "/labels/valid/Aggregator.groovy"
                ).toURI()
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
                VERSION_NAMES,
                null
        )

        escrowConfiguration = escrowConfigurationLoader.loadFullConfiguration(Collections.emptyMap())
    }

    @Test
    void testComponentWithLabelsContainsCorrectLabels() {
        def config = configLoader.loadModuleConfig()

        def componentWithLabels = config.getProperty("component_with_labels") as ConfigObject
        assert componentWithLabels.containsKey(LABELS_FIELD)

        def componentWithLabelsValue = componentWithLabels.getProperty(LABELS_FIELD) as Set<String>
        assert componentWithLabelsValue == availableLabels
    }

    @Test
    void testComponentWithoutLabelsNotContainsLabels() {
        def config = configLoader.loadModuleConfig()

        def componentWithoutLabels = config.getProperty("component_without_labels") as ConfigObject
        assert !componentWithoutLabels.containsKey(LABELS_FIELD)
    }

    @Test
    void testComponentWithSubcomponentContainsCorrectLabels() {
        def escrowModule = escrowConfiguration.escrowModules.get("component_with_subcomponent_and_labels")
        def moduleConfig = escrowModule.getModuleConfigurations().first()

        assertThat(moduleConfig.labels).isEqualTo(firstAvailableLabel)
    }

    @Test
    void testSubcomponentJoinsParentLabels() {
        def escrowModule = escrowConfiguration.escrowModules.get("inner_component_with_labels")
        def moduleConfig = escrowModule.getModuleConfigurations().first()

        assertThat(moduleConfig.labels).isEqualTo(availableLabels)
    }

    @Test
    void testSubcomponentJoinsOnlyParentLabels() {
        def escrowModule = escrowConfiguration.escrowModules.get("inner_component_without_labels")
        def moduleConfig = escrowModule.getModuleConfigurations().first()

        assertThat(moduleConfig.labels).isEqualTo(firstAvailableLabel)
    }
}
