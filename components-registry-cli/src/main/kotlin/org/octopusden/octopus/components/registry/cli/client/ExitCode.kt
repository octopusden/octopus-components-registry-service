package org.octopusden.octopus.components.registry.cli.client

import java.io.IOException

/**
 * Process exit codes for crsctl. The numeric values are part of the CLI contract (scripts depend on
 * them), so they are pinned explicitly.
 */
enum class ExitCode(val code: Int) {
    /** Successful completion. */
    OK(0),

    /** Bad invocation — unknown flags, missing required args, unresolved target, etc. */
    USAGE(2),

    /** The requested resource does not exist (HTTP 404). */
    NOT_FOUND(3),

    /** Authentication or authorization is required/insufficient (HTTP 401/403). */
    AUTH_REQUIRED(4),

    /** Server-side failure or a transport problem (HTTP 5xx, IOException). */
    SERVER(5),
}

/**
 * Maps thrown errors to an [ExitCode].
 */
object ExitCodes {
    fun fromThrowable(t: Throwable): ExitCode = when (t) {
        is CrsApiException -> fromHttpStatus(t.httpStatus)
        is IOException -> ExitCode.SERVER
        is ConfigResolutionException -> ExitCode.USAGE
        else -> ExitCode.SERVER
    }

    fun fromHttpStatus(status: Int): ExitCode = when (status) {
        404 -> ExitCode.NOT_FOUND
        401, 403 -> ExitCode.AUTH_REQUIRED
        in 500..599 -> ExitCode.SERVER
        else -> ExitCode.SERVER
    }
}
