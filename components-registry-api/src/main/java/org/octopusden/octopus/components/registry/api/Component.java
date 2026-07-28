package org.octopusden.octopus.components.registry.api;

import org.octopusden.octopus.components.registry.api.enums.ProductTypes;

import java.util.Map;

public interface Component extends SubComponent {
    ProductTypes getProductType();
    String getDisplayName();
    Map<String, SubComponent> getSubComponents();
}
