package org.octopusden.octopus.components.registry.core.dto

/**
 * Known [ErrorResponse.errorCode] values. Lives next to the DTO so server and
 * Kotlin clients share one set of constants; the wire value is the plain
 * string (clients must tolerate values not listed here).
 */
object ErrorCodes {
    /** Stale `version` on a concurrent-edit check — reload and re-apply. */
    const val OPTIMISTIC_LOCK = "OPTIMISTIC_LOCK"

    /** The write lost a row-lock race (e.g. a deadlock with a concurrent write) and was rolled back unchanged — retry it as is. */
    const val LOCK_CONFLICT = "LOCK_CONFLICT"

    /** A uniqueness invariant was violated (distribution GAV, jira projectKey+versionPrefix, docker image name, component name). */
    const val UNIQUENESS_VIOLATION = "UNIQUENESS_VIOLATION"

    /** Database integrity constraint rejected the write (duplicate or invalid data). */
    const val DATA_INTEGRITY = "DATA_INTEGRITY"

    /** A build parameters write disagrees with RMS's registered ACTUAL value for an intersecting range. */
    const val RMS_REGISTERED_VALUE_CONFLICT = "RMS_REGISTERED_VALUE_CONFLICT"

    /** The live RMS check for a build parameters write failed, timed out, or was ambiguous. */
    const val RMS_UNAVAILABLE = "RMS_UNAVAILABLE"
}
