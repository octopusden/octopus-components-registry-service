package org.octopusden.octopus.escrow.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ConfigHelper {

    public static final String CUSTOMERS_MAPPING_CONFIG_FILE = "mapping" + File.separator + "customersMapping.properties";
    public static final String DEFAULT_MAIN_CONFIG_FILE = "Aggregator.groovy";
    public static final String PATH_TO_CONFIG = "pathToConfig";
    private static final String SUPPORTED_GROUP_IDS = "supportedGroupIds";
    private static final String SUPPORTED_SYSTEMS = "supportedSystems";

    private final Environment environment;

    public ConfigHelper(Environment environment) {
        this.environment = environment;
    }
    public String moduleConfigUrl(String param) {
        if (environment.containsProperty(param)) {
            return environment.getRequiredProperty(param);
        }
        return environment.getRequiredProperty(PATH_TO_CONFIG) + File.separator
                + environment.getProperty("mainConfigFile", DEFAULT_MAIN_CONFIG_FILE);
    }

    public String moduleConfigUrl() {
        return moduleConfigUrl("configName");
    }

    public List<String> supportedGroupIds() {
        if (environment.containsProperty(SUPPORTED_GROUP_IDS)) {
            final String value = environment.getRequiredProperty(SUPPORTED_GROUP_IDS);
            return Arrays.stream(value.split(",")).map(String::trim).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    public List<String> supportedSystems() {
        if (environment.containsProperty(SUPPORTED_SYSTEMS)) {
            final String value = environment.getRequiredProperty(SUPPORTED_SYSTEMS);
            return Arrays.stream(value.split(",")).map(String::trim).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
