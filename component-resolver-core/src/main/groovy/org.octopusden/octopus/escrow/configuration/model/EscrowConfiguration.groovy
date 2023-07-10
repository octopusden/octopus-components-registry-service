package org.octopusden.octopus.escrow.configuration.model

import org.octopusden.releng.versions.VersionNames

class EscrowConfiguration {

    Map<String, EscrowModule> escrowModules = new HashMap<>()

    EscrowModule defaultConfiguration

    VersionNames versionNames

    @Override
    String toString() {
        return "EscrowConfiguration{" +
                "escrowModules=" + escrowModules +
                ", defaultConfiguration=" + defaultConfiguration +
                '}';
    }
}
