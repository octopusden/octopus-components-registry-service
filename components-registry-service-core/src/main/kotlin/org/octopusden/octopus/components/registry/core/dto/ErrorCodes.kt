package org.octopusden.octopus.components.registry.core.dto

/**
 * Known [ErrorResponse.errorCode] values. Lives next to the DTO so server and
 * Kotlin clients share one set of constants; the wire value is the plain
 * string (clients must tolerate values not listed here).
 */
object ErrorCodes {
    /** Stale `version` on a concurrent-edit check — reload and re-apply. */
    const val OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK"

    /** A uniqueness invariant was violated (distribution GAV, jira projectKey+versionPrefix, docker image name, component name). */
    const val UNIQUENESS_VIOLATION = "UNIQUENESS_VIOLATION"

    /** Database integrity constraint rejected the write (duplicate or invalid data). */
    const val DATA_INTEGRITY = "DATA_INTEGRITY"
}
