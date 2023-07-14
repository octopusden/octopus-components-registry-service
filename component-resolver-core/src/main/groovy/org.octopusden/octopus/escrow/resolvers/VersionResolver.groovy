package org.octopusden.octopus.escrow.resolvers

import org.octopusden.octopus.escrow.ModelConfigPostProcessor
import org.octopusden.octopus.escrow.configuration.loader.EscrowConfigurationLoader
import org.octopusden.octopus.releng.dto.ComponentVersion
import groovy.transform.TypeChecked
import org.octopusden.releng.versions.VersionNames

import java.util.stream.Collectors
import java.util.stream.Stream

@TypeChecked
class VersionResolver extends AbstractResolver {

    VersionNames versionNames

    VersionResolver(EscrowConfigurationLoader escrowConfigurationLoader, VersionNames versionNames) {
        super(escrowConfigurationLoader)
        this.versionNames = versionNames
    }

    List<String> resolve(ComponentVersion componentRelease) {
        def config = getEscrowModuleConfig(componentRelease)
        if (config == null) {
            return null
        }
        if (config.octopusVersion == null) {
            return Collections.emptyList()
        }
        def processor = new ModelConfigPostProcessor(componentRelease, versionNames)
        return Stream.of(config.octopusVersion?.split(","))
            .map({ processor.resolveVariables(it) })
            .collect(Collectors.toList())
    }
}
