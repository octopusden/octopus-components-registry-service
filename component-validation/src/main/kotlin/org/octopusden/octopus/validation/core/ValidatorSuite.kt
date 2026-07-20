package org.octopusden.octopus.validation.core

/**
 * The group of checks for a domain (e.g. TeamCity). Running the validators is identical for
 * every domain, so it lives once here (inheritance); the services each validator needs are
 * handed to it via its own constructor (composition), never inherited.
 *
 * Decision D6 (implementer's discretion, see docs/teamcity-validation-implementation-brief.md
 * §4): a validator that throws must not sink the whole suite. [validate] catches any
 * [RuntimeException] from an individual validator and turns it into a [Status.ERROR] result
 * carrying the exception message, so one bad check never loses the others' results.
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
