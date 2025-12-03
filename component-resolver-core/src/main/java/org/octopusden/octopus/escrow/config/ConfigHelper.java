package org.octopusden.octopus.escrow.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.octopusden.octopus.components.registry.api.enums.ProductTypes;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ConfigHelper {

    public static final String CUSTOMERS_MAPPING_CONFIG_FILE = "mapping" + File.separator + "customersMapping.properties";
    public static final String DEFAULT_MAIN_CONFIG_FILE = "Aggregator.groovy";
    public static final String PATH_TO_CONFIG = "pathToConfig";
    private static final String SUPPORTED_GROUP_IDS = "components-registry.supportedGroupIds";
    private static final String SUPPORTED_SYSTEMS = "components-registry.supportedSystems";

    private static final String VERSION_NAME_SERVICE_BRANCH = "components-registry.version-name.service-branch";
    private static final String VERSION_NAME_SERVICE = "components-registry.version-name.service";
    private static final String VERSION_NAME_MINOR = "components-registry.version-name.minor";

    private static final String PRODUCT_TYPE_C = "components-registry.product-type.c";
    private static final String PRODUCT_TYPE_K = "components-registry.product-type.k";
    private static final String PRODUCT_TYPE_D = "components-registry.product-type.d";
    private static final String PRODUCT_TYPE_D_DB = "components-registry.product-type.ddb";

    private static final String COPYRIGHT_PATH = "components-registry.copyright-path";

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

    public Map<ProductTypes, String> productTypes() {
        Map<ProductTypes, String> result = new EnumMap(ProductTypes.class);
        result.put(ProductTypes.PT_C, environment.getRequiredProperty(PRODUCT_TYPE_C));
        result.put(ProductTypes.PT_K, environment.getRequiredProperty(PRODUCT_TYPE_K));
        result.put(ProductTypes.PT_D, environment.getRequiredProperty(PRODUCT_TYPE_D));
        result.put(ProductTypes.PT_D_DB, environment.getRequiredProperty(PRODUCT_TYPE_D_DB));
        return result;
    }

    public String serviceBranch() {
        return environment.getRequiredProperty(VERSION_NAME_SERVICE_BRANCH);
    }

    public String service() {
        return environment.getRequiredProperty(VERSION_NAME_SERVICE);
    }

    public String minor() {
        return environment.getRequiredProperty(VERSION_NAME_MINOR);
    }

    public Path copyrightPath() {
        String copyrightPathStr = environment.getProperty(COPYRIGHT_PATH);
        if(copyrightPathStr == null) {
            return null;
        }

        Path copyrightPath = Paths.get(copyrightPathStr);
        if (!Files.isDirectory(copyrightPath)) {
            throw new IllegalStateException("Copyright path '" + copyrightPath + "' is not a directory");
        }
        return copyrightPath;
    }
}
