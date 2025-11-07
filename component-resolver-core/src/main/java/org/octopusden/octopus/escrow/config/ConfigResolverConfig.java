package org.octopusden.octopus.escrow.config;

import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException;
import org.octopusden.octopus.escrow.resolvers.IReleaseInfoResolver;
import org.octopusden.octopus.escrow.resolvers.ReleaseInfoResolver;
import org.octopusden.releng.versions.VersionNames;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

@Configuration
public class ConfigResolverConfig {

    @Bean
    ConfigHelper configHelper(Environment environment) {
        return new ConfigHelper(environment);
    }

    @Bean
    String moduleConfigUrl(ConfigHelper configHelper) {
        return configHelper.moduleConfigUrl("moduleConfigUrl");
    }

    @Bean
    VersionNames versionNames(ConfigHelper configHelper) {
        return new VersionNames(
                configHelper.serviceBranch(),
                configHelper.service(),
                configHelper.minor()
        );
    }
    @Bean
    public IReleaseInfoResolver releaseInfoResolver(ConfigHelper configHelper, VersionNames versionNames) {
        return new ReleaseInfoResolver(escrowConfigurationLoader(configHelper, versionNames));
    }

    @Bean
    public EscrowConfigurationLoader escrowConfigurationLoader(ConfigHelper configHelper, VersionNames versionNames) {
        return new EscrowConfigurationLoader(
                configLoader(configHelper, versionNames),
                configHelper.supportedGroupIds(),
                configHelper.supportedSystems(),
                versionNames,
                configHelper.copyrightPath()
        );
    }

    @Bean
    public ConfigLoader configLoader(ConfigHelper configHelper, VersionNames versionNames) {
        try {
            String moduleConfigUrl = Objects.requireNonNull(moduleConfigUrl(configHelper));
            URL configFileResource = new File(moduleConfigUrl).toURI().toURL();
            return new ConfigLoader(ComponentRegistryInfo.createFromURL(configFileResource), versionNames, configHelper.productTypes());
        } catch (MalformedURLException e) {
            throw new EscrowConfigurationException("Malformed url: " + moduleConfigUrl(configHelper), e);
        }
    }

}
