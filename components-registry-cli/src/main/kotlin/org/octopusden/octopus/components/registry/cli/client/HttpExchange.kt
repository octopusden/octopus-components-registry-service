package org.octopusden.octopus.components.registry.cli.client

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Small functional seam over the JDK HTTP client so tests can substitute a fake without touching the
 * network. Production code uses [JdkHttpExchange]; tests provide an in-memory implementation.
 */
fun interface HttpExchange {
    fun send(request: HttpRequest): HttpResponse<String>
}

/**
 * Default [HttpExchange] backed by [java.net.http.HttpClient], always reading the body as a UTF-8
 * String.
 */
class JdkHttpExchange(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : HttpExchange {
    override fun send(request: HttpRequest): HttpResponse<String> =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}
