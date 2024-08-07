package org.octopusden.octopus.escrow.resolvers

import groovy.transform.TypeChecked
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.releng.dto.ComponentVersion

@TypeChecked
class DistributionResolver implements IDistributionResolver {
    private EscrowConfiguration configuration
    private static final Logger LOG = LogManager.getLogger(DistributionResolver.class)

    DistributionResolver(EscrowConfiguration configuration) {
        this.configuration = configuration
    }

    @Override
    Distribution resolveDistribution(ComponentVersion componentVersion) {
        if (configuration == null) {
            LOG.warn("Configuration for $componentVersion is not found")
            return null
        }

        def moduleConfig = EscrowConfigurationLoader.getEscrowModuleConfig(configuration, componentVersion)

        if (moduleConfig == null) {
            LOG.warn("Failed to resolve artifact $componentVersion in Escrow Config")
            return null
        }

        return moduleConfig.distribution
    }
}
