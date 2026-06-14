package org.octopusden.octopus.components.registry.cli.client

/**
 * Thrown when the CRS responds with a non-2xx status. Carries the HTTP status plus the parsed
 * `ErrorResponse` fields (errorCode is optional per the contract; errorMessage is required).
 *
 * The command layer maps this to an [ExitCode] via [ExitCodes.fromThrowable].
 */
class CrsApiException(
    val httpStatus: Int,
    val errorCode: String?,
    val errorMessage: String,
) : RuntimeException("HTTP $httpStatus: $errorMessage")
