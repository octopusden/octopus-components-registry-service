package org.octopusden.octopus.escrow.resolvers

import org.junit.Test
import org.octopusden.octopus.components.registry.api.enums.EscrowGenerationMode
import org.octopusden.octopus.components.registry.api.escrow.Escrow
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.releng.dto.ComponentVersion

import java.nio.file.Paths

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull
import static org.octopusden.octopus.escrow.TestConfigUtils.loadFromURL

class EscrowModeResolverTest {

    @Test
    void testResolvedConfigurationEscrowAuto() {
        def releaseInfo = withConfigResolver("/escrowmode/Aggregator.groovy").resolveRelease(ComponentVersion.create("TEST_COMPONENT3", "1.0.106"))

        assertNotNull(releaseInfo.escrow)
        Escrow escrow = releaseInfo.escrow.get()
        assertEquals(EscrowGenerationMode.AUTO, escrow.generation)
    }

    @Test
    void testResolvedConfigurationEscrowManual() {
        def releaseInfo = withConfigResolver("/escrowmode/Aggregator.groovy").resolveRelease(ComponentVersion.create("TEST_COMPONENT3", "1.0.108"))

        assertNotNull(releaseInfo.escrow)
        Escrow escrow = releaseInfo.escrow.get()
        assertEquals(EscrowGenerationMode.MANUAL, escrow.generation)
    }

    @Test
    void testResolvedConfigurationComponentsEscrowMode() {
        def releaseInfo = withConfigResolver("/escrowmode/Aggregator.groovy").resolveRelease(ComponentVersion.create("jdk", "1.0.108"))

        assertNotNull(releaseInfo.escrow)
        Escrow escrow = releaseInfo.escrow.get()
        assertEquals(EscrowGenerationMode.UNSUPPORTED, escrow.generation)
    }

    @Test
    void testResolvedConfigurationComponentsEscrowMode2() {
        def releaseInfo = withConfigResolver("/escrowmode/Aggregator.groovy").resolveRelease(ComponentVersion.create("jdk-manual", "1.0.108"))

        assertNotNull(releaseInfo.escrow)
        Escrow escrow = releaseInfo.escrow.get()
        assertEquals(EscrowGenerationMode.MANUAL, escrow.generation)
    }

    private static ReleaseInfoResolver withConfigResolver(String resource) {
        def aggregatorPath = Paths.get(ReleaseInfoResolverTest.class.getResource(resource).toURI())

        return new ReleaseInfoResolver(
                loadFromURL(
                        ComponentRegistryInfo.createFromFileSystem(aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString())
                ))
    }

}
