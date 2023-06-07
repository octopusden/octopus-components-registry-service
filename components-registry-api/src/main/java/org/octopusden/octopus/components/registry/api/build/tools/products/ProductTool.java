package org.octopusden.octopus.components.registry.api.build.tools.products;

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool;
import org.octopusden.octopus.components.registry.api.enums.ProductTypes;

public interface ProductTool extends BuildTool {
    ProductTypes getType();
    String getVersion();
    String getSettingsProperty();
}
