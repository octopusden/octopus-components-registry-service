package org.octopusden.octopus.escrow.resolvers

import org.junit.Test
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException

import static groovy.test.GroovyAssert.shouldFail
import static org.octopusden.octopus.escrow.TestConfigUtils.loadConfiguration

class DocConfigurationLoaderTest {

    /**
     * Test that an exception is thrown when the required 'Doc.component' field is missing in the configuration.
     */
    @Test
    void testRequiredDocComponent() {
        def exception = shouldFail(Exception.class) {
            loadConfiguration("doc/invalidSimpleConfig.groovy")
        }
        assert exception.message.contains("Doc.component is not specified")
    }

    /**
     * Test that an exception is thrown when the specified 'Doc.component' does not exist in the configuration.
     */
    @Test
    void testDocWithNonexistentComponent() {
        def exception = shouldFail(EscrowConfigurationException.class) {
            loadConfiguration("doc/invalidSimpleConfigWithNonExistentDocComponent.groovy")
        }
        assert exception.message.contains("Doc.component 'doc_component' module is not found for module 'component'")
    }

    /**
     * Test that an exception is thrown when the 'Doc.component' refers to a module that itself has a 'Doc' configuration.
     */
    @Test
    void testDocWithDoc() {
        def exception = shouldFail(EscrowConfigurationException.class) {
            loadConfiguration("doc/invalidSimpleConfigDocWithDoc.groovy")
        }
        assert exception.message.contains("Doc component doc_component must not have 'doc' property")
    }

    /**
     * Test that an exception is thrown when the 'Doc.component' does not have GAV defined.
     */
    @Test
    void testDocWithoutGAV() {
        def exception = shouldFail(EscrowConfigurationException.class) {
            loadConfiguration("doc/invalidSimpleConfigWithoutGAV.groovy")
        }
        assert exception.message.contains("Doc component 'doc_component' must have distribution.GAV defined (artifact-based documentation) for module 'component'")
    }

    @Test
    void testResolvedDoc() {
        EscrowConfiguration configuration = loadConfiguration("doc/simpleConfig.groovy")
        def moduleConfigs = configuration.escrowModules.get("component")
        assert moduleConfigs != null, "Module configurations should not be null"
        def moduleConfig = moduleConfigs.moduleConfigurations[0]
        assert moduleConfig != null, "Module configuration should not be null"
        assert moduleConfig.doc != null, "Doc settings should be present"
        assert moduleConfig.doc.component() == "doc_component", "Doc.component should be 'doc_component'"
        assert moduleConfig.doc.majorVersion() == "1.2", "Doc.majorVersion should be '1.2'"
    }
}
