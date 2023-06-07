package org.octopusden.octopus.escrow.config;


import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo;
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader;
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader;
import org.octopusden.octopus.escrow.resolvers.IJiraParametersResolver;
import org.octopusden.octopus.escrow.resolvers.JiraParametersResolver;
import org.apache.commons.lang3.Validate;
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

    private ConfigHelper configHelper;

    @Autowired
    private ResourceLoader resourceLoader;

    @Autowired
    private Environment environment;

    String moduleConfigUrl() {
        return getConfigHelper().moduleConfigUrl("configName");
    }

    private ConfigHelper getConfigHelper() {
        if (configHelper == null) {
            configHelper = new ConfigHelper(environment);
        }
        return configHelper;
    }

    @Bean
    String customersMappingConfigUrl() {
        return environment.containsProperty(PATH_TO_CONFIG) ?
                environment.getRequiredProperty(PATH_TO_CONFIG) + File.separator + CUSTOMERS_MAPPING_CONFIG_FILE : null;
    }

    @Bean
    public IJiraParametersResolver componentInfoResolver() throws IOException {
        return new JiraParametersResolver(escrowConfigurationLoader(), Collections.emptyMap());
    }

    @Bean
    public EscrowConfigurationLoader escrowConfigurationLoader() throws IOException {
        return new EscrowConfigurationLoader(configLoader(), getConfigHelper().supportedGroupIds(), getConfigHelper().supportedSystems());
    }

    @Bean
    public ConfigLoader configLoader() throws IOException {
        String moduleConfigUrl = moduleConfigUrl();
        Validate.notNull(moduleConfigUrl, "configName system property is not set");
        Resource resource = resourceLoader.getResource(moduleConfigUrl);
        Validate.notNull(resource, "cant load resource from moduleConfigUrl " + moduleConfigUrl);
        URL url = resource.getURL();
        return new ConfigLoader(ComponentRegistryInfo.createFromURL(url));
    }

}
