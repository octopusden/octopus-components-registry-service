package org.octopusden.octopus.escrow.exceptions;

import org.octopusden.octopus.escrow.exceptions.ComponentResolverException;

public class EscrowConfigurationException extends ComponentResolverException {

    public EscrowConfigurationException(String message) {
        super(message);
    }

    public EscrowConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
