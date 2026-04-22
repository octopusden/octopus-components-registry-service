package org.octopusden.octopus.components.registry.client

import com.fasterxml.jackson.databind.ObjectMapper
import feign.Request
import feign.RequestTemplate
import feign.Response
import feign.RetryableException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException

class ComponentsRegistryServiceErrorDecoderTest {

    private val decoder = ComponentsRegistryServiceErrorDecoder(ObjectMapper())

    private fun stubResponse(status: Int, reason: String = "", body: String? = null): Response {
        val request = Request.create(
            Request.HttpMethod.GET,
            "http://localhost/test",
            emptyMap(),
            null,
            RequestTemplate()
        )
        val builder = Response.builder()
            .status(status)
            .reason(reason)
            .request(request)
            .headers(emptyMap())
        if (body != null) {
            builder.body(body, Charsets.UTF_8)
            builder.headers(mapOf("content-type" to listOf("application/json")))
        }
        return builder.build()
    }

    @Test
    fun `503 returns RetryableException`() {
        val ex = decoder.decode("test#method", stubResponse(503, "Service Unavailable"))
        assertTrue(ex is RetryableException) { "Expected RetryableException for 503, got ${ex::class.simpleName}" }
    }

    @Test
    fun `404 with json body returns NotFoundException`() {
        val body = """{"errorMessage":"not found"}"""
        val ex = decoder.decode("test#method", stubResponse(404, "Not Found", body))
        assertTrue(ex is NotFoundException) { "Expected NotFoundException for 404, got ${ex::class.simpleName}" }
    }

    @Test
    fun `500 returns generic FeignException`() {
        val ex = decoder.decode("test#method", stubResponse(500, "Internal Server Error"))
        assertTrue(ex is feign.FeignException) { "Expected FeignException for 500, got ${ex::class.simpleName}" }
        assertTrue(ex !is RetryableException) { "500 should not be retryable" }
    }
}
