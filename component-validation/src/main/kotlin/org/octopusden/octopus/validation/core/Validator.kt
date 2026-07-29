package org.octopusden.octopus.validation.core

/**
 * A single check: one yes/no question, one [validate] call, one [ValidationResult]. Every
 * validator exposes the same shape (different question, identical signature) so a
 * [ValidatorSuite] can run them uniformly.
 */
interface Validator<in I> {
    val type: ValidationType

    fun validate(input: I): ValidationResult
}
