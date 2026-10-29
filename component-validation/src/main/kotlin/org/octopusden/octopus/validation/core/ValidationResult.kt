package org.octopusden.octopus.validation.core

/**
 * The answer to a single validator's yes/no question.
 *
 * [NOT_APPLICABLE] is what lets a check say "I looked, this doesn't apply here" instead of
 * silently producing nothing — it removes the "clean vs didn't-apply" ambiguity.
 */
enum class Status {
    OK,
    WARNING,
    ERROR,
    NOT_APPLICABLE,
}

/**
 * A feature's set of question types. Kept as a plain interface in the generic core so each
 * feature (TeamCity today, others later) can supply its own closed enum without the core
 * depending on it.
 */
interface ValidationType {
    val id: String
}

/**
 * The result of one [Validator]: which check it is ([type]), the answer ([status]), and an
 * optional human-readable [message]. Deliberately flat — no typed findings, no per-occurrence
 * detail; the outer world (UI, storage) just filters by type.
 */
data class ValidationResult(
    val type: ValidationType,
    val status: Status,
    val message: String? = null,
)
