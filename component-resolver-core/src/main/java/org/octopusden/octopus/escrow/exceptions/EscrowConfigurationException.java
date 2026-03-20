package org.octopusden.octopus.escrow.exceptions;

public class EscrowConfigurationException extends ComponentResolverException {

    public EscrowConfigurationException(String message) {
        super(message);
    }

    public EscrowConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
