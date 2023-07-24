package org.octopusden.octopus.escrow.config;


import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.resolvers.IJiraParametersResolver;
import org.octopusden.octopus.escrow.resolvers.JiraParametersResolver;
import org.apache.commons.lang3.Validate;
import org.octopusden.releng.versions.VersionNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;

import static org.octopusden.octopus.escrow.config.ConfigHelper.CUSTOMERS_MAPPING_CONFIG_FILE;
import static org.octopusden.octopus.escrow.config.ConfigHelper.PATH_TO_CONFIG;

@Configuration
public class JiraParametersResolverConfig {

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private Environment environment;

    String moduleConfigUrl(ConfigHelper configHelper) {
        return configHelper.moduleConfigUrl("configName");
    }

    @Bean
    ConfigHelper configHelper() {
        return new ConfigHelper(environment);
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
    String customersMappingConfigUrl() {
        return environment.containsProperty(PATH_TO_CONFIG) ?
                environment.getRequiredProperty(PATH_TO_CONFIG) + File.separator + CUSTOMERS_MAPPING_CONFIG_FILE : null;
    }

    @Bean
    public IJiraParametersResolver componentInfoResolver(VersionNames versionNames, EscrowConfigurationLoader escrowConfigurationLoader) throws IOException {
        return new JiraParametersResolver(escrowConfigurationLoader, Collections.emptyMap());
    }

    @Bean
    public EscrowConfigurationLoader escrowConfigurationLoader(ConfigHelper configHelper, VersionNames versionNames, ConfigLoader configLoader) throws IOException {
        return new EscrowConfigurationLoader(configLoader,
                configHelper.supportedGroupIds(),
                configHelper.supportedSystems(),
                versionNames
        );
    }

    @Bean
    public ConfigLoader configLoader(ConfigHelper configHelper, VersionNames versionNames) throws IOException {
        String moduleConfigUrl = moduleConfigUrl(configHelper);
        Validate.notNull(moduleConfigUrl, "configName system property is not set");
        Resource resource = resourceLoader.getResource(moduleConfigUrl);
        Validate.notNull(resource, "cant load resource from moduleConfigUrl " + moduleConfigUrl);
        URL url = resource.getURL();
        return new ConfigLoader(ComponentRegistryInfo.createFromURL(url), versionNames, configHelper.productTypes());
    }

}
