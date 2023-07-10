package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.ReleaseInfo
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowModuleConfig
import org.octopusden.octopus.releng.dto.ComponentVersion
import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.octopusden.releng.versions.VersionNames

@TypeChecked
class ReleaseInfoResolver extends AbstractResolver implements IReleaseInfoResolver {
    private static final Logger LOG = LogManager.getLogger(ReleaseInfoResolver.class)

    public static final String DEFAULT_SETTINGS = "Defaults"
    public static final String TOOLS_SETTINGS = "Tools"

    private final VersionNames versionNames

    ReleaseInfoResolver(EscrowConfigurationLoader escrowConfigurationLoader) {
        super(escrowConfigurationLoader)
        this.versionNames = escrowConfigurationLoader.getVersionNames()
    }

    @Override
    ReleaseInfo resolveRelease(ComponentVersion componentRelease) {
        return resolveRelease(componentRelease, new HashMap<String, String>())
    }

    @Override
    ReleaseInfo resolveRelease(ComponentVersion componentRelease, Map<String, String> params) {
        return resolveComponentRelease(getEscrowModuleConfig(componentRelease, false, params), componentRelease, versionNames)
    }

    static ReleaseInfo resolveComponentRelease(EscrowModuleConfig configuration, ComponentVersion componentRelease, VersionNames versionNames) {
        if (configuration == null) {
            LOG.warn("Configuration for $componentRelease is not found")
            return null
        }

        def vcsSettings = createVCSRootWithFormattedBranch(configuration.getVcsSettings(), versionNames, componentRelease.componentName, componentRelease.version)
        def releaseInfo = ReleaseInfo.create(vcsSettings,
                configuration.getBuildSystem(),
                configuration.getBuildFilePath(),
                configuration.getBuildConfiguration(),
                configuration.distribution,
                configuration.isDeprecated(),
                configuration.escrow
        )
        ModelConfigPostProcessor modelConfigPostProcessor = new ModelConfigPostProcessor(componentRelease, versionNames)
        return modelConfigPostProcessor.resolveVariables(releaseInfo)
    }
}
