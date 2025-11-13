package org.octopusden.octopus.components.registry.api.enums;

public enum EscrowGenerationMode {
    //Escrow automation is supported. Escrow Test configuration supported
    AUTO,
    //Escrow Test configuration to be created.
    MANUAL,
    //Escrow Test configuration should not be created.
    UNSUPPORTED
}
