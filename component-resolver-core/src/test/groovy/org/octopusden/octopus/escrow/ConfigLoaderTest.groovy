package org.octopusden.octopus.escrow

import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.exceptions.ComponentResolverException
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import org.octopusden.releng.versions.VersionNames

import static org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo.createFromFileSystem

@TypeChecked
class ConfigLoaderTest {

    public static final String TEST_VALUE = "1234"
    public static final String TEST_PARAMETER = "pkgj_version"
    public static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC")
    public static final Map<ProductTypes, String> PRODUCT_TYPES = new EnumMap(ProductTypes.class) {
        {
            put(ProductTypes.PT_C, "PT_C");
            put(ProductTypes.PT_K, "PT_K");
            put(ProductTypes.PT_D, "PT_D");
            put(ProductTypes.PT_D_DB, "PT_D_DB");
        }};

    @Test
    void testLoad() {
        ConfigLoader loader = fromClassPath("testModuleConfig.groovy")
        loader.loadModuleConfig()
    }

    private static ConfigLoader fromClassPath(String config) {
        return new ConfigLoader(ComponentRegistryInfo.fromClassPath((config)), VERSION_NAMES, PRODUCT_TYPES)
    }

    @Test
    void testLoadModuleConfigWithoutEnums() {
        def loader = fromClassPath("moduleConfigWithoutEnums.groovy")
        loader.loadModuleConfig()
    }

    @Test
    void testModularConfig() {
        def loader = new ConfigLoader(createFromFileSystem("src/test/resources", "experimental/Aggregator.groovy"),
                VERSION_NAMES, PRODUCT_TYPES)
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
        def loader = new ConfigLoader(createFromFileSystem("vcs-resolver/src/test/resources", "testModuleConfig.groovy"),
                VERSION_NAMES, PRODUCT_TYPES)
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
