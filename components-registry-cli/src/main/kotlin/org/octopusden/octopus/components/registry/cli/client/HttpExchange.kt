package org.octopusden.octopus.components.registry.cli.client

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Default TCP connect timeout for the production HTTP client (seconds). */
private const val CONNECT_TIMEOUT_SECONDS = 10L

/**
 * Small functional seam over the JDK HTTP client so tests can substitute a fake without touching the
 * network. Production code uses [JdkHttpExchange]; tests provide an in-memory implementation.
 */
fun interface HttpExchange {
    fun send(request: HttpRequest): HttpResponse<String>
}

/**
 * Default [HttpExchange] backed by [java.net.http.HttpClient], always reading the body as a UTF-8
 * String. The default client carries a connect timeout so a dead host fails fast rather than
 * hanging; the per-request read timeout is set by [CrsClient].
 */
class JdkHttpExchange(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : HttpExchange {
    override fun send(request: HttpRequest): HttpResponse<String> =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}
