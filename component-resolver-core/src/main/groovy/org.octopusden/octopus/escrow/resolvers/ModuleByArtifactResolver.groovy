package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.exceptions.EscrowConfigurationException
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.escrow.configuration.validation.EscrowModuleConfigMatcher
import org.octopusden.octopus.releng.dto.ComponentVersion
import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.maven.artifact.Artifact
import org.octopusden.releng.versions.NumericVersionFactory
import org.octopusden.releng.versions.VersionNames
import org.octopusden.releng.versions.VersionRangeFactory

@TypeChecked
class ModuleByArtifactResolver implements IModuleByArtifactResolver {
    private static final Logger LOG = LogManager.getLogger(ModuleByArtifactResolver.class)

    private final VersionNames versionNames
    private EscrowConfiguration escrowConfiguration
    private final EscrowModuleConfigMatcher escrowModuleConfigMatcher
    private EscrowConfigurationLoader configLoader
    private Map<String, String> params
    private VersionRangeFactory versionRangeFactory
    private NumericVersionFactory numericVersionFactory

    ModuleByArtifactResolver(VersionNames versionNames) {
        this.versionNames = versionNames
        versionRangeFactory = new VersionRangeFactory(versionNames)
        numericVersionFactory = new NumericVersionFactory(versionNames)
        escrowModuleConfigMatcher = new EscrowModuleConfigMatcher(versionRangeFactory, numericVersionFactory)
    }

    ModuleByArtifactResolver(EscrowConfiguration escrowConfiguration) {
        this(escrowConfiguration.versionNames)
        this.escrowConfiguration = escrowConfiguration
    }

    ModuleByArtifactResolver(EscrowConfigurationLoader configLoader, Map<String, String> params) {
        this(configLoader.getVersionNames())
        Objects.requireNonNull(configLoader)
        this.configLoader = configLoader
        this.params = params
    }

    void setEscrowConfiguration(EscrowConfiguration escrowConfiguration) {
        this.escrowConfiguration = escrowConfiguration
    }

    ModuleByArtifactResolver(EscrowConfigurationLoader configLoader) {
        this(configLoader, Collections.emptyMap() as Map<String, String>)
    }

    @Override
    ComponentVersion resolveComponentByArtifact(Artifact mavenArtifact) {
        if (mavenArtifact.getVersion().endsWith("-SNAPSHOT")) {
            LOG.warn("Ignore snapshot " +  mavenArtifact.getVersion())
            return null
        }
        def matchedComponentConfigurations = [:]
        String matchedModuleName
        for (String module: escrowConfiguration.escrowModules.keySet()) {
            def escrowModule = escrowConfiguration.escrowModules.get(module)
            for (EscrowModuleConfig moduleConfig : escrowModule.moduleConfigurations) {
                if (escrowModuleConfigMatcher.match(mavenArtifact, moduleConfig)) {
                    LOG.debug("$mavenArtifact matches versionRange ${moduleConfig.getVersionRangeString()}")
                    matchedModuleName = module
                    matchedComponentConfigurations["$module:${moduleConfig.getVersionRangeString()}"] = moduleConfig
                }
            }
        }
        if (matchedComponentConfigurations.size() == 1) {
            Objects.requireNonNull(matchedModuleName)
            def componentRelease = ComponentVersion.create(matchedModuleName, mavenArtifact.version)
            LOG.info("$mavenArtifact belongs to $componentRelease")
            return componentRelease
        } else if (matchedComponentConfigurations.isEmpty()) {
            LOG.warn("Configuration is not found for $mavenArtifact in escrow module config")
        } else {
            throw new EscrowConfigurationException("Configuration File Conflict. More than one configuration matches $mavenArtifact in EscrowConfigFile:  ${matchedComponentConfigurations.keySet().join(",")}")
        }
        return null
    }

    @Override
    void reloadComponentsRegistry() {
        escrowConfiguration = configLoader.loadFullConfiguration(params)
    }

}
