package org.octopusden.octopus.components.registry.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.core.exceptions.RepositoryNotPreparedException
import feign.Response
import feign.RetryableException
import feign.codec.ErrorDecoder

class ComponentsRegistryServiceErrorDecoder(val objectMapper: ObjectMapper) : ErrorDecoder.Default() {

    override fun decode(methodKey: String?, response: Response?): Exception {
        if (isRetryable(response)) {
            // Let super parse Retry-After header first; reuse the RetryableException it builds
            // (which carries the parsed retry timestamp). If super returns a plain FeignException
            // (no Retry-After header present), synthesize our own with null retryAfter.
            val superException = super.decode(methodKey, response)
            if (superException is RetryableException) {
                return superException
            }
            return RetryableException(
                response!!.status(),
                response.reason() ?: "Service Unavailable",
                response.request().httpMethod(),
                null as java.util.Date?,
                response.request()
            )
        }
        return getErrorResponse(response)
                ?.let {
                    val status = response?.status()!!
                    errorResponseCodes.getOrDefault(status) { super.decode(methodKey, response) }
                            .invoke(it)
                } ?: super.decode(methodKey, response)
    }

    private fun getErrorResponse(response: Response?): ErrorResponse? {
        return response?.let { res ->
            res.headers()["content-type"]
                    ?.find { it.contains("application/json") }
                    ?.let {
                        try {
                            res.body()
                                    ?.asInputStream()
                                    .use { inputStream -> objectMapper.readValue(inputStream, ErrorResponse::class.java) }
                        } catch (e: Exception) {
                            null
                        }
                    }
        }
    }

    companion object {
        private val errorResponseCodes: Map<Int, (ErrorResponse) -> Exception> = mapOf(
                404 to { errorResponse: ErrorResponse -> NotFoundException(errorResponse.errorMessage) },
                425 to { errorResponse: ErrorResponse -> RepositoryNotPreparedException(errorResponse.errorMessage) }
        )
        private val retryableStatusCodes = setOf(503)
    }

    private fun isRetryable(response: Response?) = response?.status() in retryableStatusCodes
}
