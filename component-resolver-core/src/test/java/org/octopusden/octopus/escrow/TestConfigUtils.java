package org.octopusden.octopus.escrow;

import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration;

import java.util.Arrays;
import java.util.List;

public class TestConfigUtils {

    public static final List<String> SUPPORTED_GROUP_IDS = Arrays.asList("org.octopusden.octopus","io.bcomponent");
    public static final List<String> SUPPORTED_SYSTEMS = Arrays.asList("NONE", "CLASSIC", "ALFA");

    public static EscrowConfiguration loadConfiguration(String config) {
        EscrowConfigurationLoader escrowConfigurationLoader = escrowConfigurationLoader(config);
        return escrowConfigurationLoader.loadFullConfiguration(null);
    }

    public static EscrowConfigurationLoader escrowConfigurationLoader(String config) {
        return loadFromURL(ComponentRegistryInfo.fromClassPath(config));
    }

    public static EscrowConfigurationLoader loadFromURL(ComponentRegistryInfo resource) {
        return new EscrowConfigurationLoader(
                new ConfigLoader(resource),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS
        );
    }
}
