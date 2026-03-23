package org.octopusden.octopus.components.registry.light.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.octopusden.octopus.components.registry.light.client.dto.ErrorResponse;
import org.octopusden.octopus.components.registry.light.client.exceptions.NotFoundException;
import org.octopusden.octopus.components.registry.light.client.exceptions.RepositoryNotPreparedException;
import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class ComponentsRegistryServiceErrorDecoder extends ErrorDecoder.Default {

    private final ObjectMapper objectMapper;

    private static final Map<Integer, Function<ErrorResponse, Exception>> ERROR_RESPONSE_CODES = new HashMap<>();

    static {
        ERROR_RESPONSE_CODES.put(404, (ErrorResponse errorResponse) -> new NotFoundException(errorResponse.getErrorMessage()));
        ERROR_RESPONSE_CODES.put(425, (ErrorResponse errorResponse) -> new RepositoryNotPreparedException(errorResponse.getErrorMessage()));
    }

    public ComponentsRegistryServiceErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response != null) {
            ErrorResponse errorResponse = getErrorResponse(response);
            if (errorResponse != null) {
                int status = response.status();
                Function<ErrorResponse, Exception> errorHandler = ERROR_RESPONSE_CODES.get(status);
                if (errorHandler != null) {
                    return errorHandler.apply(errorResponse);
                }
            }
        }
        return super.decode(methodKey, response);
    }

    private ErrorResponse getErrorResponse(Response response) {
        Collection<String> contentTypes = response.headers().getOrDefault("content-type", Collections.emptyList());
        if (contentTypes != null) {
            for (String contentType : contentTypes) {
                if (contentType.contains("application/json")) {
                    try {
                        if (response.body() != null) {
                            try (InputStream inputStream = response.body().asInputStream()) {
                                return objectMapper.readValue(inputStream, ErrorResponse.class);
                            }
                        }
                    } catch (IOException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
