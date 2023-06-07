package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.TestConfigUtils
import org.octopusden.octopus.releng.dto.ComponentVersion
import groovy.transform.TypeChecked
import org.junit.Test

@TypeChecked
class VersionResolverTest {

    @Test
    void testVersionDefaultInheritance() {
        loadAndCheckVersion("test-default", "1.0", { sv, rv ->
            assert sv != "03.49"
            assert rv == []
        })
    }

    @Test
    void testVersionSpecifiedDirectly() {
        loadAndCheckVersion("root-component", "3.47", { sv, rv ->
            assert sv == "03.47"
            assert rv == ["03.47"]
        })
    }

    @Test
    void testVersionWithPlaceholder() {
        loadAndCheckVersion("root-component", "3.48.30.4.5", { sv, rv ->
            assert sv == '$major02.$minor02.$service.$fix.$build04,03.47.$service.$fix.$build04'
            assert rv == ['03.48.30.4.0005', '03.47.30.4.0005']
        })
    }

    @Test
    void testVersionSubComponentSpecifiedDirectly() {
        loadAndCheckVersion("sub-component-one", "3.48", { sv, rv ->
            assert sv == "03.47"
            assert rv == ["03.47"]
        })
    }

    @Test
    void testVersionSubComponentInheritance() {
        loadAndCheckVersion("sub-component-two", "3.48", { sv, rv ->
            assert sv == null
            assert rv == []
        })
    }

    private static void loadAndCheckVersion(String component, String version, Closure<Void> versionChecking) {
        def componentVersion = ComponentVersion.create(component, version)
        def resolver = new VersionResolver(TestConfigUtils.escrowConfigurationLoader("VersionInheritance.groovy"))

        def moduleConfig = resolver.getEscrowModuleConfig(componentVersion)
        assert moduleConfig != null

        def sourceVersion = moduleConfig.octopusVersion
        def resolvedVersion = resolver.resolve(componentVersion)
        versionChecking(sourceVersion, resolvedVersion)
    }
}
