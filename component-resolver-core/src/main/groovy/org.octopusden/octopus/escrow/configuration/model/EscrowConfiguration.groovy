package org.octopusden.octopus.escrow.configuration.model

class EscrowConfiguration {

    Map<String, EscrowModule> escrowModules = new HashMap<>()

    EscrowModule defaultConfiguration

    @Override
    String toString() {
        return "EscrowConfiguration{" +
                "escrowModules=" + escrowModules +
                ", defaultConfiguration=" + defaultConfiguration +
                '}';
    }
}
