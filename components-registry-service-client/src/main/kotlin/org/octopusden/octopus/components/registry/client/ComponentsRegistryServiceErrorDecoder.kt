package org.octopusden.octopus.components.registry.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.components.registry.core.exceptions.RepositoryNotPreparedException
import feign.Response
import feign.codec.ErrorDecoder

class ComponentsRegistryServiceErrorDecoder(val objectMapper: ObjectMapper) : ErrorDecoder.Default() {

    override fun decode(methodKey: String?, response: Response?): Exception {
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
    }
}
