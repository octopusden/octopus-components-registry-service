package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.components.registry.api.build.tools.products.PTKProductTool
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.junit.Test
import java.nio.file.Paths

import static org.junit.Assert.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNull
import static org.junit.Assert.assertTrue
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES

class ReleaseInfoResolverTest {

    @Test
    void testResolvedHotfixConfiguration() {
        def resolver = getResolver("/hotfix/Aggregator.groovy")

        def exception = assertThrows(EscrowConfigurationException, {
            resolver.resolveRelease(ComponentVersion.create("component_hotfix", "1.0.107.9-9"))
        })

        assertTrue("Expected error message to contain validation failure details", exception.message.contains("hotfixVersionFormat '\$major.\$minor.\$service-\$build' doesn't start with buildVersionFormat '\$major.\$minor.\$service-\$fix'"))
    }

    @Test
    void testResolvedConfiguration() {
        def releaseInfo = getResolver("/production/Aggregator.groovy").resolveRelease(ComponentVersion.create("TEST_COMPONENT3", "1.0.107"))
        assertTrue(releaseInfo.distribution.explicit())
        assertTrue(releaseInfo.distribution.external())
        assertEquals('org.octopusden.octopus.test:octopusmpi:war,org.octopusden.octopus.test:octopusacs:war,org.octopusden.octopus.test:demo:war,' +
                'file:///${env.CONF_PATH}/NDC_DDC_Configuration_Builder/${major}.${minor}/NDC-DDC-Configuration-Builder-${major}.${minor}.${service}.exe', releaseInfo.distribution.GAV())
    }

    @Test
    void testResolvedDistributionConfiguration() {
        def resolver = getResolver("/production/Aggregator.groovy")
        def releaseInfo162720 = resolver.resolveRelease(ComponentVersion.create("app", "1.6.2720"))
        def releaseInfo173630 = resolver.resolveRelease(ComponentVersion.create("app", "1.7.3630"))
        def vcsRoot = releaseInfo162720.vcsSettings.versionControlSystemRoots.find { vcsRoot -> vcsRoot.name == "release-script" }
        assertNotNull(vcsRoot)
        assertEquals("hotfix:1.6", vcsRoot.hotfixBranch)
        assertTrue(releaseInfo162720.distribution.explicit())
        assertTrue(releaseInfo162720.distribution.external())
        assertEquals('file:///as-1.6', releaseInfo162720.distribution.GAV())
        assertEquals('org.octopusden.octopus.distribution.server:app:zip:aix7_1,org.octopusden.octopus.distribution.server:app:zip:aix7_2,org.octopusden.octopus.distribution.server:app:zip:rhel6-linux-i386-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel6-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel7-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel8-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:solaris11-sparc-nojdk,org.octopusden.octopus.distribution.server:app:zip:windows-x64-nojdk', releaseInfo173630.distribution.GAV())
    }

    @Test
    void testExcludedDistributionValidationConfigurationValid() {
        def resolver = getResolver("/validation/valid/Aggregator.groovy")
        resolver.resolveRelease(ComponentVersion.create("component_test", "1.2.3630"))
    }

    @Test
    void testExcludedDistributionValidationConfigurationInvalid() {
        def resolver = getResolver("/validation/invalid/Aggregator.groovy")
        def exception = assertThrows(EscrowConfigurationException, {
            resolver.resolveRelease(ComponentVersion.create("component_test", "1.2.3630"))
        })
        assertTrue(exception.message.contains("External explicitly distributed components must define at least one distribution coordinate (distribution->GAV, DEB, RPM, or Docker) in 'component_test'."))
    }

    @Test
    void testResolvedBuildTools() {
        def plCommonReleaseInfo = getResolver("/production/Aggregator.groovy").resolveRelease(ComponentVersion.create("component_commons", "1.2.3630"))
        assertTrue(plCommonReleaseInfo.buildParameters.buildTools.any { buildTool -> buildTool instanceof PTKProductTool })
    }

    @Test
    void testReleaseInfoResolverDebRpm() {
        def resolver = getResolver("/deb-rpm/Aggregator.groovy")
        def releaseInfo3 = resolver.resolveRelease(ComponentVersion.create("logcomp", "3.0.1"))
        def releaseInfo4 = resolver.resolveRelease(ComponentVersion.create("logcomp", "4.0.1"))
        assertEquals('org.octopusden.octopus.logcomp:logcomp', releaseInfo3.distribution.GAV())
        assertEquals('logcomp_${version}-1_amd64.deb', releaseInfo3.distribution.DEB())
        assertNull(releaseInfo3.distribution.RPM())
        assertNull(releaseInfo4.distribution.GAV())
        assertEquals('logcomp_${version}-1_amd64.deb', releaseInfo4.distribution.DEB())
        assertEquals('logcomp_${version}.el8.noarch.rpm', releaseInfo4.distribution.RPM())
    }

    private static ReleaseInfoResolver getResolver(String resource) {
        def aggregatorPath = Paths.get(ReleaseInfoResolverTest.class.getResource(resource).toURI())
        def escrowConfigurationLoader = new EscrowConfigurationLoader(
                new ConfigLoader(
                        ComponentRegistryInfo.createFromFileSystem(aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString()),
                        VERSION_NAMES,
                        PRODUCT_TYPES
                ),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS,
                VERSION_NAMES
        )
        return new ReleaseInfoResolver(escrowConfigurationLoader)
    }
}
