package org.octopusden.octopus.escrow;

import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration;
import org.octopusden.releng.versions.NumericVersionFactory;
import org.octopusden.releng.versions.VersionNames;
import org.octopusden.releng.versions.VersionRangeFactory;

import java.util.Arrays;
import java.util.List;

public class TestConfigUtils {

    public static final List<String> SUPPORTED_GROUP_IDS = Arrays.asList("org.octopusden.octopus","io.bcomponent");
    public static final List<String> SUPPORTED_SYSTEMS = Arrays.asList("NONE", "CLASSIC", "ALFA");

    public static final VersionNames VERSION_NAMES = new VersionNames("serviceCBranch", "serviceC", "minorC");

    public static final VersionRangeFactory VERSION_RANGE_FACTORY = new VersionRangeFactory(VERSION_NAMES);
    public static final NumericVersionFactory NUMERIC_VERSION_FACTORY = new NumericVersionFactory(VERSION_NAMES);

    public static EscrowConfiguration loadConfiguration(String config) {
        EscrowConfigurationLoader escrowConfigurationLoader = escrowConfigurationLoader(config);
        return escrowConfigurationLoader.loadFullConfiguration(null);
    }

    public static EscrowConfigurationLoader escrowConfigurationLoader(String config) {
        return loadFromURL(ComponentRegistryInfo.fromClassPath(config));
    }

    public static EscrowConfigurationLoader loadFromURL(ComponentRegistryInfo resource) {
        return new EscrowConfigurationLoader(
                new ConfigLoader(resource, VERSION_NAMES),
                SUPPORTED_GROUP_IDS,
                SUPPORTED_SYSTEMS,
                VERSION_NAMES
        );
    }
}
