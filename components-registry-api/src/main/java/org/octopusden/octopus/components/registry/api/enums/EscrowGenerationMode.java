package org.octopusden.octopus.components.registry.api.enums;

/**
 * Enum representing the modes of escrow generation.
 * AUTO - Escrow automation is supported.
 * MANUAL - Escrow Test configuration to be created.
 * UNSUPPORTED - Escrow Test configuration should not be created.
 */
public enum EscrowGenerationMode {
    AUTO,
    MANUAL,
    UNSUPPORTED
}
