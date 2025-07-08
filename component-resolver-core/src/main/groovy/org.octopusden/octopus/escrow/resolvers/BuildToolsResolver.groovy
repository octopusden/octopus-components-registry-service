package org.octopusden.octopus.escrow.resolvers

import groovy.transform.TypeChecked
import groovy.util.logging.Slf4j
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean
import org.octopusden.octopus.components.registry.api.beans.ProductToolBean
import org.octopusden.octopus.components.registry.api.build.tools.BuildTool
import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.components.registry.dsl.jackson.JacksonFactory
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.dto.DistributionEntity
import org.octopusden.octopus.escrow.utilities.DistributionUtilities
import org.octopusden.octopus.releng.dto.ComponentVersion

@TypeChecked
@Slf4j
class BuildToolsResolver implements IBuildToolsResolver {
    private EscrowConfiguration escrowConfiguration

    BuildToolsResolver() {}

    BuildToolsResolver(final EscrowConfiguration escrowConfiguration) {
        this.escrowConfiguration = escrowConfiguration
    }

    void setEscrowConfiguration(EscrowConfiguration escrowConfiguration) {
        this.escrowConfiguration = escrowConfiguration
    }

    @Override
    Collection<BuildTool> getComponentBuildTools(ComponentVersion component) {
        getComponentBuildTools(component, null, false)
    }

    @Override
    Collection<BuildTool> getComponentBuildTools(ComponentVersion component, String version) {
        return getComponentBuildTools(component, version, false)
    }

    @Override
    Collection<BuildTool> getComponentBuildTools(ComponentVersion component, String projectVersion, boolean ignoreRequired) {
        def componentConfiguration = EscrowConfigurationLoader.getEscrowModuleConfig(escrowConfiguration, component)
        def buildTools = componentConfiguration?.buildConfiguration?.buildTools
        Collection<BuildTool> overriddenBuildTools = new ArrayList<>()
        def buildConfiguration = componentConfiguration?.buildConfiguration
        if (!ignoreRequired && buildConfiguration?.requiredProject) {
            PTCProductToolBean toolBean = new PTCProductToolBean()
            toolBean.version = projectVersion ?: buildConfiguration.projectVersion
            overriddenBuildTools.add(toolBean)
        }

        if (buildTools) {
            if (projectVersion) {
                buildTools.forEach { BuildTool tool ->
                    if (tool instanceof PTCProductToolBean || tool instanceof PTKProductToolBean) {
                        def productToolBean
                        if (tool instanceof PTCProductToolBean) {
                            productToolBean = clone(tool, PTCProductToolBean.class)
                        } else {
                            productToolBean = clone(tool, PTKProductToolBean.class)
                        }
                        ((ProductToolBean) productToolBean).version = projectVersion
                        overriddenBuildTools.add((BuildTool) productToolBean)
                    } else {
                        overriddenBuildTools.add(tool)
                    }
                }
            } else {
                overriddenBuildTools.addAll(buildTools)
            }
        }
        overriddenBuildTools
    }

    @Override
    Optional<String> getProductMappedComponent(ProductTypes productType) {
        return Optional.ofNullable(escrowConfiguration.escrowModules.find { k, v -> v.moduleConfigurations.find { module -> module.productType == productType } != null }?.key)
    }

    @Override
    Map<String, ProductTypes> getComponentProductMapping() {
        return escrowConfiguration.escrowModules
                .collectEntries { componentKey, escrowModule ->
                    [componentKey, escrowModule.moduleConfigurations.find { it.productType != null }?.productType] }
                .findAll { k, v -> v != null } as Map<String, ProductTypes>
    }

    @Override
    Collection<DistributionEntity> getDistributionEntities(ComponentVersion component) {
        def componentConfiguration = EscrowConfigurationLoader.getEscrowModuleConfig(escrowConfiguration, component)
        if (componentConfiguration.distribution == null || !componentConfiguration.distribution.explicit()) {
            return Collections.emptyList()
        }
        return DistributionUtilities.parseDistributionGAV(componentConfiguration.distribution.GAV())
    }

    private static final <T> T clone(Object object, Class<T> clazz) {
        def mapper = JacksonFactory.instance.objectMapper
        def outputStream = new ByteArrayOutputStream()
        mapper.writeValue(outputStream, object)
        return mapper.readValue(new ByteArrayInputStream(outputStream.toByteArray()), clazz)
    }
}
