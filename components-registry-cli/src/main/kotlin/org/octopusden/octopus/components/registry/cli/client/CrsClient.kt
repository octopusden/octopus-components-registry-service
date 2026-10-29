package org.octopusden.octopus.components.registry.cli.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import org.octopusden.octopus.components.registry.cli.model.ErrorResponse
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Thin read-only client over the CRS REST API.
 *
 * Construction takes a base URL, an optional bearer token, and an injectable [HttpExchange] seam so
 * tests can run without a network. The default seam is [JdkHttpExchange].
 *
 * Responses:
 * - 2xx with `application/json` -> deserialized into the requested type via [getJson].
 * - 2xx with `text/plain` (the as-code endpoints) -> raw String via [getText].
 * - non-2xx -> body parsed as [ErrorResponse] and re-thrown as [CrsApiException] (including 401/403,
 *   so the command layer can map to AUTH_REQUIRED).
 */
class CrsClient(
    baseUrl: String,
    private val token: String? = null,
    private val exchange: HttpExchange = JdkHttpExchange(),
) {
    /** Base URL with any trailing slash stripped so path joining is unambiguous. */
    private val baseUrl: String = baseUrl.trimEnd('/')

    /**
     * GET [path] (+ optional [query]) and deserialize a 2xx JSON body into [T] using [serializer].
     * Sends `Accept: application/json`.
     */
    fun <T> getJson(
        path: String,
        serializer: KSerializer<T>,
        query: QueryParams? = null,
    ): T {
        val response = get(path, query, "application/json")
        return try {
            Json.decodeFromString(serializer, response.body() ?: "")
        } catch (e: SerializationException) {
            throw CrsApiException(
                httpStatus = response.statusCode(),
                errorCode = null,
                errorMessage = "Failed to parse response body: ${e.message}",
            )
        }
    }

    /**
     * GET [path] (+ optional [query]) and return the raw 2xx body verbatim (for the as-code /
     * text/plain endpoints). Sends `Accept: text/plain`.
     */
    fun getText(
        path: String,
        query: QueryParams? = null,
    ): String {
        val response = get(path, query, "text/plain")
        return response.body() ?: ""
    }

    private fun get(
        path: String,
        query: QueryParams?,
        accept: String,
    ): java.net.http.HttpResponse<String> {
        val request = buildGet(path, query, accept)
        val response = exchange.send(request)
        if (response.statusCode() in 200..299) {
            return response
        }
        throw toApiException(response)
    }

    private fun buildGet(
        path: String,
        query: QueryParams?,
        accept: String,
    ): HttpRequest {
        val builder = HttpRequest
            .newBuilder()
            .uri(buildUri(path, query))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .GET()
            .header("Accept", accept)
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private fun buildUri(
        path: String,
        query: QueryParams?,
    ): URI {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val suffix = if (query != null && !query.isEmpty()) "?${query.encode()}" else ""
        return URI.create("$baseUrl$normalizedPath$suffix")
    }

    private fun toApiException(response: java.net.http.HttpResponse<String>): CrsApiException {
        val body = response.body()
        val parsed = if (body.isNullOrBlank()) {
            null
        } else {
            try {
                Json.decodeFromString(ErrorResponse.serializer(), body)
            } catch (e: SerializationException) {
                null
            }
        }
        return CrsApiException(
            httpStatus = response.statusCode(),
            errorCode = parsed?.errorCode,
            errorMessage = parsed?.errorMessage ?: "HTTP ${response.statusCode()}",
        )
    }

    companion object {
        /** Per-request timeout (seconds); a slow/stalled response surfaces as a SERVER exit. */
        private const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
