package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.components.registry.api.build.tools.products.PTKProductTool
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.releng.dto.ComponentVersion
import org.junit.Test

import java.nio.file.Paths

import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES

class ReleaseInfoResolverTest {

    @Test
    void testResolvedConfiguration() {
        def releaseInfo = getResolver().resolveRelease(ComponentVersion.create("TEST_COMPONENT3", "1.0.107"))
        assertTrue(releaseInfo.distribution.explicit())
        assertTrue(releaseInfo.distribution.external())
        assertEquals('org.octopusden.octopus.test:octopusmpi:war,org.octopusden.octopus.test:octopusacs:war,org.octopusden.octopus.test:demo:war,' +
                'file:///${env.CONF_PATH}/NDC_DDC_Configuration_Builder/${major}.${minor}/NDC-DDC-Configuration-Builder-${major}.${minor}.${service}.exe', releaseInfo.distribution.GAV())
    }

    @Test
    void testResolvedDistributionConfiguration() {
        def releaseInfo162720 = getResolver().resolveRelease(ComponentVersion.create("app", "1.6.2720"))
        def releaseInfo173630 = getResolver().resolveRelease(ComponentVersion.create("app", "1.7.3630"))
        assertTrue(releaseInfo162720.distribution.explicit())
        assertTrue(releaseInfo162720.distribution.external())
        assertEquals('file:///as-1.6', releaseInfo162720.distribution.GAV())
        assertEquals('org.octopusden.octopus.distribution.server:app:zip:aix7_1,org.octopusden.octopus.distribution.server:app:zip:aix7_2,org.octopusden.octopus.distribution.server:app:zip:rhel6-linux-i386-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel6-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel7-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:rhel8-linux-x64-nojdk,org.octopusden.octopus.distribution.server:app:zip:solaris11-sparc-nojdk,org.octopusden.octopus.distribution.server:app:zip:windows-x64-nojdk', releaseInfo173630.distribution.GAV())
    }

    @Test
    void testResolvedBuildTools() {
        def plCommonReleaseInfo = getResolver().resolveRelease(ComponentVersion.create("component_commons", "1.2.3630"))
        assertTrue(plCommonReleaseInfo.buildParameters.buildTools.any { buildTool -> buildTool instanceof PTKProductTool })
    }

    private static ReleaseInfoResolver getResolver() {
        def aggregatorPath = Paths.get(ReleaseInfoResolverTest.class.getResource("/production/Aggregator.groovy").toURI())
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
