package org.octopusden.octopus.components.registry.light.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.octopusden.octopus.components.registry.light.client.dto.ErrorResponse;
import org.octopusden.octopus.components.registry.light.client.exceptions.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The decoder is constructed with a CALLER-supplied ObjectMapper. The server's
 * error body may grow fields (e.g. {@code errorCode}); a strict mapper
 * (FAIL_ON_UNKNOWN_PROPERTIES) must still parse it — otherwise typed 404/425
 * exceptions silently degrade to generic Feign errors. Guarded by
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the DTO.
 */
class ComponentsRegistryServiceErrorDecoderTest {

    private static Response jsonResponse(int status, String body) {
        Map<String, java.util.Collection<String>> headers = new HashMap<>();
        headers.put("content-type", Collections.singletonList("application/json"));
        return Response.builder()
                .status(status)
                .reason("test")
                .request(Request.create(Request.HttpMethod.GET, "/rest/api/2/components/unknown",
                        Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .headers(headers)
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    @Test
    void strictMapperParsesErrorBodyWithUnknownFields() {
        ObjectMapper strictMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ComponentsRegistryServiceErrorDecoder decoder = new ComponentsRegistryServiceErrorDecoder(strictMapper);

        Exception decoded = decoder.decode(
                "ComponentsRegistryServiceClient#getComponent",
                jsonResponse(404,
                        "{\"errorMessage\":\"Component 'unknown' is not found\","
                                + "\"errorCode\":\"NOT_FOUND\",\"someFutureField\":42}"));

        Assertions.assertInstanceOf(NotFoundException.class, decoded);
        Assertions.assertEquals("Component 'unknown' is not found", decoded.getMessage());
    }

    @Test
    void errorCodeIsExposedAndNullableForOlderServers() throws Exception {
        ObjectMapper strictMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        ErrorResponse withCode = strictMapper.readValue(
                "{\"errorMessage\":\"uniqueness violation: …\",\"errorCode\":\"UNIQUENESS_VIOLATION\"}",
                ErrorResponse.class);
        Assertions.assertEquals("UNIQUENESS_VIOLATION", withCode.getErrorCode());

        ErrorResponse legacyBody = strictMapper.readValue(
                "{\"errorMessage\":\"plain old error\"}",
                ErrorResponse.class);
        Assertions.assertEquals("plain old error", legacyBody.getErrorMessage());
        Assertions.assertNull(legacyBody.getErrorCode());
    }
}
