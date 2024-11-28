package org.octopusden.octopus.escrow.resolvers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.model.Distribution;
import org.octopusden.octopus.releng.dto.ComponentVersion;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.octopusden.octopus.escrow.TestConfigUtils.PRODUCT_TYPES;
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_GROUP_IDS;
import static org.octopusden.octopus.escrow.TestConfigUtils.SUPPORTED_SYSTEMS;
import static org.octopusden.octopus.escrow.TestConfigUtils.VERSION_NAMES;

@DisplayName("Components registry DistributionResolver test")
public class DistributionResolverTest {

    private IDistributionResolver distributionResolver;
    private static EscrowConfigurationLoader escrowConfigurationLoader;

    @BeforeAll
    static void initBeforeClass() throws URISyntaxException {
        final Path aggregatorPath = Paths.get(Objects.requireNonNull(DistributionResolverTest.class.getResource("/distribution-docker/Aggregator.groovy")).toURI());
        escrowConfigurationLoader = new EscrowConfigurationLoader(
                new ConfigLoader(
                        ComponentRegistryInfo.createFromFileSystem(aggregatorPath.getParent().toString(),
                                aggregatorPath.getFileName().toString()
                        ),
                        VERSION_NAMES, PRODUCT_TYPES),
                SUPPORTED_GROUP_IDS, SUPPORTED_SYSTEMS, VERSION_NAMES);
    }

    @BeforeEach
    void setUp() {
        distributionResolver = new DistributionResolver(escrowConfigurationLoader.loadFullConfiguration(Collections.emptyMap()));
    }

    @Test
    void testDockerOfTestComponent() {
        Distribution distribution = distributionResolver.resolveDistribution(ComponentVersion.create("test-component", "1.3.49"));
        assertNotNull(distribution);
        assertEquals("test-component/image:${version}", distribution.docker());
    }

}
