package org.octopusden.octopus.escrow.configuration.validation

import org.octopusden.octopus.components.registry.api.enums.ProductTypes
import org.octopusden.octopus.escrow.configuration.loader.ComponentRegistryInfo
import org.octopusden.octopus.escrow.configuration.loader.ConfigLoader
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.releng.versions.VersionNames
import org.slf4j.LoggerFactory

/**
 * Standalone validator that doesn't depend on Gradle Task infrastructure.
 * Can be executed in a separate JVM via JavaExec task to avoid Kotlin Scripting API 
 * classloader issues with Java 21 in Gradle buildscript.
 */
class StandaloneValidator {
    
    private static final def log = LoggerFactory.getLogger(StandaloneValidator.class)
    
    static void main(String[] args) {
        try {
            log.info("=== Component Registry Validation Starting ===")
            log.info("Java version: ${System.getProperty("java.version")}")
            log.info("Working directory: ${System.getProperty("user.dir")}")
            
            // Load configuration from system properties
            def basePath = getRequiredProperty("cr.basePath")
            def mainConfigFileName = getRequiredProperty("cr.mainConfigFileName")
            def supportedGroupIds = getRequiredProperty("cr.supportedGroupIds").split(",").collect { it.trim() }
            def supportedSystems = getRequiredProperty("cr.supportedSystems").split(",").collect { it.trim() }
            def serviceBranch = getRequiredProperty("cr.serviceBranch")
            def service = getRequiredProperty("cr.service")
            def minor = getRequiredProperty("cr.minor")
            def productTypeC = getRequiredProperty("cr.productTypeC")
            def productTypeK = getRequiredProperty("cr.productTypeK")
            def productTypeD = getRequiredProperty("cr.productTypeD")
            def productTypeDDB = getRequiredProperty("cr.productTypeDDB")
            
            log.info("\nConfiguration:")
            log.info("  basePath: $basePath")
            log.info("  mainConfigFileName: $mainConfigFileName")
            log.info("  supportedGroupIds: ${supportedGroupIds.join(", ")}")
            log.info("  supportedSystems: ${supportedSystems.join(", ")}")
            log.info("  serviceBranch: $serviceBranch")
            log.info("  service: $service")
            log.info("  minor: $minor")
            
            // Create product type map
            def productTypeMap = new EnumMap(ProductTypes.class)
            productTypeMap.put(ProductTypes.PT_C, productTypeC)
            productTypeMap.put(ProductTypes.PT_K, productTypeK)
            productTypeMap.put(ProductTypes.PT_D, productTypeD)
            productTypeMap.put(ProductTypes.PT_D_DB, productTypeDDB)
            
            log.info("\nLoading component registry...")
            log.info("Product types: ${productTypeMap.toMapString()}")
            
            // Create config loader
            def componentRegistryInfo = ComponentRegistryInfo.createFromFileSystem(basePath, mainConfigFileName)
            def versionNames = new VersionNames(serviceBranch, service, minor)
            def loader = new ConfigLoader(componentRegistryInfo, versionNames, productTypeMap)
            
            // Load escrow configuration
            def escrowConfigurationLoader = new EscrowConfigurationLoader(
                loader,
                supportedGroupIds,
                supportedSystems,
                versionNames
            )
            
            EscrowConfiguration configuration = escrowConfigurationLoader.loadFullConfiguration(null)
            assert configuration != null
            
            def escrowModules = configuration.escrowModules
            log.info("Loaded ${escrowModules.size()} components/modules")
            
            log.info("\nValidating components...")
            log.info("=" * 80)
            
            // Basic validation - if it loaded without exceptions, configuration is valid
            escrowModules.each { componentName, module ->
                log.debug("✓ Component '$componentName' loaded successfully")
                module.moduleConfigurations.each { moduleConfig ->
                    log.trace("  Module: ${moduleConfig.componentDisplayName}")
                }
            }
            
            log.info("=" * 80)
            log.info("✓ Validation completed successfully for ${escrowModules.size()} components")
            System.exit(0)
            
        } catch (Exception e) {
            log.error("✗ Validation failed", e)
            System.exit(1)
        }
    }
    
    private static String getProperty(String name) {
        return System.getProperty(name)
    }
    
    private static String getRequiredProperty(String name) {
        def value = System.getProperty(name)
        if (value == null) {
            throw new IllegalArgumentException("Required system property '$name' is not set")
        }
        return value
    }
}
