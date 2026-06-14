package org.octopusden.octopus.components.registry.cli.model

import kotlinx.serialization.Serializable

/**
 * Mirror of v4.json `ErrorResponse` — the structured error body returned on every non-2xx status.
 *
 * The spec marks only `errorMessage` as required; `errorCode` is optional. Both are surfaced so the
 * core layer can render structured stderr errors.
 */
@Serializable
data class ErrorResponse(
    val errorMessage: String,
    val errorCode: String? = null,
)
