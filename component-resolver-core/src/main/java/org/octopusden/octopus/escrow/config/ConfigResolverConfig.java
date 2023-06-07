package org.octopusden.octopus.escrow.config;

import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException;
import org.octopusden.octopus.escrow.resolvers.IReleaseInfoResolver;
import org.octopusden.octopus.escrow.resolvers.ReleaseInfoResolver;
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
    public IReleaseInfoResolver releaseInfoResolver(ConfigHelper configHelper) {
        return new ReleaseInfoResolver(escrowConfigurationLoader(configHelper));
    }

    @Bean
    public EscrowConfigurationLoader escrowConfigurationLoader(ConfigHelper configHelper) {
        return new EscrowConfigurationLoader(configLoader(configHelper), configHelper.supportedGroupIds(), configHelper.supportedSystems());
    }

    @Bean
    public ConfigLoader configLoader(ConfigHelper configHelper) {
        try {
            String moduleConfigUrl = Objects.requireNonNull(moduleConfigUrl(configHelper));
            URL configFileResource = new File(moduleConfigUrl).toURI().toURL();
            return new ConfigLoader(ComponentRegistryInfo.createFromURL(configFileResource));
        } catch (MalformedURLException e) {
            throw new EscrowConfigurationException("Malformed url: " + moduleConfigUrl(configHelper), e);
        }
    }

}
