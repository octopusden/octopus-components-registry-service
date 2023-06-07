package org.octopusden.octopus.escrow.exceptions;

public class ComponentResolverException extends RuntimeException {
    public ComponentResolverException(String message) {
        super(message);
    }

    public ComponentResolverException(String message, Throwable cause) {
        super(message, cause);
    }
}
