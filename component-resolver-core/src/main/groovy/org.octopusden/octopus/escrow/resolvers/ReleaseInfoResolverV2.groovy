package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.ReleaseInfo
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.escrow.configuration.model.EscrowConfiguration
import org.octopusden.octopus.releng.dto.ComponentVersion
import groovy.transform.TypeChecked

@TypeChecked
class ReleaseInfoResolverV2 implements IReleaseInfoResolverV2 {
    private EscrowConfiguration escrowConfiguration

    ReleaseInfoResolverV2(EscrowConfiguration escrowConfiguration) {
        this.escrowConfiguration = escrowConfiguration
    }

    @Override
    ReleaseInfo resolveRelease(ComponentVersion componentVersion) {
        def configuration = EscrowConfigurationLoader.getEscrowModuleConfig(escrowConfiguration, componentVersion)
        return ReleaseInfoResolver.resolveComponentRelease(configuration, componentVersion)
    }
}
