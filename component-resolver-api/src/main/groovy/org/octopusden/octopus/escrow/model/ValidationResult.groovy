package org.octopusden.octopus.escrow.model


class ValidationResult implements IValidationResult, Serializable {
    private static final long serialVersionUID = -661232368456812121L;

    Set<String> errors = new HashSet<>()
    def containsMavenPublish = false

    void handleError(String message) {
        errors.add(message)
    }

    void handleErrors(List<String> errors) {
        this.errors.addAll(errors)
    }

    Set<String> getErrors() {
        return errors;
    }

    boolean isValid() {
        return errors.size() == 0
    }

    void componentContainsMavenPublish(boolean value) {
        containsMavenPublish = value
    }

    @Override
    boolean componentContainsMavenPublish() {
        return containsMavenPublish
    }
}
