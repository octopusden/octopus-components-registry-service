package org.octopusden.octopus.escrow.model

interface IValidationResult {
    void handleError(String message)

    void handleErrors(List<String> errors)

    Set<String> getErrors()

    boolean isValid()

    void componentContainsMavenPublish(boolean value)

    boolean componentContainsMavenPublish()

}
