package org.octopusden.octopus.escrow

import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test

import static org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo.createFromFileSystem

@TypeChecked
class ConfigLoaderTest {

    public static final String TEST_VALUE = "1234"
    public static final String TEST_PARAMETER = "pkgj_version"

    @Test
    void testLoad() {
        ConfigLoader loader = fromClassPath("testModuleConfig.groovy")
        loader.loadModuleConfig()
    }

    private static ConfigLoader fromClassPath(String config) {
        return new ConfigLoader(ComponentRegistryInfo.fromClassPath((config)))
    }

    @Test
    void testLoadModuleConfigWithoutEnums() {
        def loader = fromClassPath("moduleConfigWithoutEnums.groovy")
        loader.loadModuleConfig()
    }

    @Test
    void testModularConfig() {
        def loader = new ConfigLoader(createFromFileSystem("src/test/resources", "experimental/Aggregator.groovy"));
        checkAggregatorConfig(loader)
    }

    @TypeChecked(value = TypeCheckingMode.SKIP)
    private static void checkAggregatorConfig(ConfigLoader loader) {
        def configObject = loader.loadModuleConfig()

        assert configObject != null;

        def defaults = configObject.get("Defaults")
        assert defaults.repositoryType == RepositoryType.MERCURIAL;

        def myModule = configObject.myModule
        assert myModule."(,0),[0,)".groupId == "org.octopusden.octopus.mymodule"
        assert myModule."(,0),[0,)".tag == "zenit"
    }

    @Test
    void testModularConfigFromClassPath() {
        def loader = fromClassPath("experimental/Aggregator.groovy")
        checkAggregatorConfig(loader)
    }


    @Test
    @Ignore
    void stressTest() {
        def loader = new ConfigLoader(createFromFileSystem("vcs-resolver/src/test/resources", "testModuleConfig.groovy"))
        for (int i = 0; i < 10000; i++) {
            println i;
            loader.loadModuleConfig()
        }
        assert true
    }

    @Test
    void testInvalidAttributesInSubComponent() {
        def loader = fromClassPath("invalidAttributeInSubComponent.groovy")
        try {
            def config = loader.loadModuleConfig()
            assert config != null;
            assert false: "EscrowException should be thrown"
        } catch (ComponentResolverException e) {
            assert e.getMessage().contains("Unsupported attribute 'zenit' in component notJiraComponent");
            assert e.getMessage().contains("Unknown jira attribute 'abwgd' in notJiraComponent->defaults section");
        }
    }

    @Test
    void testInvalidLoad() {
        def loader = fromClassPath("invalidModuleConfig.groovy")
        try {
            loader.loadModuleConfig()
            assert false: "EscrowException should be thrown"
        } catch (ComponentResolverException e) {
            assert e.getMessage().contains("Unknown attribute 'invalidAttributeCvs' in bcomponent->Cvs");
            assert e.getMessage().contains("Unknown attribute 'invalidAttributeDefault' in Defaults section of escrow config file");
        }
    }

    @Test
    void testModuleWithParameters() {
        def loader = fromClassPath("moduleConfigWithParameters.groovy")

        def map = new HashMap<String, String>() {
            {
                put(TEST_PARAMETER, TEST_VALUE);
            }
        }
        def config = loader.loadModuleConfig(map);

        def testProject = config.getProperty("test-project") as ConfigObject
        assert testProject.containsKey(TEST_VALUE)
        def moduleConfig = testProject.getProperty(TEST_VALUE) as ConfigObject;
        assert moduleConfig.getProperty("vcsUrl") != null;
    }

    @Test
    void testOldAndNewJiraConfigurationConflict() {
        try {
            def loader = fromClassPath("ambiguousJiraConfig.groovy");
            loader.loadModuleConfig()
            Assert.fail("${EscrowConfigurationException.class.name} expected")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("Ambiguous jira configuration of component 'octopusweb' section [2.0,)");
        }
    }

    @Test
    void testOldAndNewVCSConfigurationConflict() {
        try {
            def loader = fromClassPath("ambiguousVCSConfig.groovy");
            loader.loadModuleConfig()
            Assert.fail("${EscrowConfigurationException.class.name} expected")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("Ambiguous VCS configuration of component 'octopusweb' section vcsSettings");
        }
    }

    @Test
    void testInvalidVCSConfigurationAttribute() {
        try {
            def loader = fromClassPath("invalid/unknownAttributeInVCSRoot.groovy");
            loader.loadModuleConfig()
            Assert.fail("${EscrowConfigurationException.class.name} expected")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("Unknown 'spartak' attribute in component->vcsSettings section of escrow config file");
            assert e.message.contains("Unknown 'zenit' attribute in cvs1->component->vcsSettings section of escrow config file");
        }
    }

    @Test
    void testInvalidJiraConfigAttribute() {
        try {
            def loader = fromClassPath("invalidJiraConfigAttribute.groovy");
            loader.loadModuleConfig()
            Assert.fail("${EscrowConfigurationException.class.name} expected")
        } catch (EscrowConfigurationException e) {
            assert e.message.contains("Unknown jira attribute 'unknownAttirubute' in bcomponent->Mercurial section of escrow config file");
        }
    }
}
