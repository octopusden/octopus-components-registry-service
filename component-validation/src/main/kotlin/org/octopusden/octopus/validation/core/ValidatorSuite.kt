package org.octopusden.octopus.validation.core

/**
 * The group of checks for a domain (e.g. TeamCity). Running validators is identical for every
 * domain, so it lives once here (inheritance); each validator's own services are supplied via its
 * constructor (composition), never inherited.
 *
 * [validate] catches any [RuntimeException] thrown by an individual validator and turns it into a
 * [Status.ERROR] result carrying the exception message, so one bad check never sinks the others.
 */
abstract class ValidatorSuite<I> {
    protected abstract val validators: List<Validator<I>>

    fun validate(input: I): List<ValidationResult> =
        validators.map { validator ->
            try {
                validator.validate(input)
            } catch (e: RuntimeException) {
                ValidationResult(
                    type = validator.type,
                    status = Status.ERROR,
                    message = "${validator.type.id} threw ${e::class.simpleName}: ${e.message}",
                )
            }
        }
}
