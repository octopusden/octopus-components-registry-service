package org.octopusden.octopus.components.registry.light.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code ignoreUnknown} is mandatory here: the decoder is constructed with a
 * caller-supplied {@link com.fasterxml.jackson.databind.ObjectMapper}, and a
 * strict mapper would otherwise throw on any field added to the server's error
 * body (e.g. {@code errorCode}) — silently degrading typed 404/425 exceptions
 * to generic Feign errors (the decoder swallows the parse failure).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ErrorResponse {
    private final String errorMessage;
    private final String errorCode;

    @JsonCreator
    public ErrorResponse(@JsonProperty("errorMessage") String errorMessage,
                         @JsonProperty("errorCode") String errorCode) {
        this.errorMessage = errorMessage;
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Machine-readable error class (nullable; absent on older servers).
     * Known values: OPTIMISTIC_LOCK, UNIQUENESS_VIOLATION, DATA_INTEGRITY.
     */
    public String getErrorCode() {
        return errorCode;
    }
}
