package org.octopusden.octopus.escrow.resolvers;

import org.octopusden.octopus.components.registry.api.build.tools.BuildTool;
import org.octopusden.octopus.components.registry.api.distribution.DistributionEntity;
import org.octopusden.octopus.components.registry.api.enums.ProductTypes;
import org.octopusden.octopus.releng.dto.ComponentVersion;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface IBuildToolsResolver {
     Collection<BuildTool> getComponentBuildTools(ComponentVersion component);
     Collection<BuildTool> getComponentBuildTools(ComponentVersion component, String version);
     Collection<BuildTool> getComponentBuildTools(ComponentVersion component, String projectVersion, boolean ignoreRequired);

     /**
      * Get component associated with {@link ProductTypes}.
      * @param productType product type
      * @return Returns component name
      */
     Optional<String> getProductMappedComponent(ProductTypes productType);

     /**
      * Get mapping components to products.
      * @return Returns mapping components to products
      */
     Map<String, ProductTypes> getComponentProductMapping();

     /**
      * Get distribution entities for explicitly external components.
      * @param component component
      * @return Returns collection of distribution entities if component explicit and external, empty collection otherwise
      */
     Collection<DistributionEntity> getDistributionEntities(ComponentVersion component);
}
