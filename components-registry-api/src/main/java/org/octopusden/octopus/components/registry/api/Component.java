package org.octopusden.octopus.components.registry.api;

import org.octopusden.octopus.components.registry.api.enums.ProductTypes;

import java.util.Map;

public interface Component extends SubComponent {
    /**
     * Get product type associated with component.
     * @return Returns product type
     */
    ProductTypes getProductType();
    String getDisplayName();
    Map<String, SubComponent> getSubComponents();
}
