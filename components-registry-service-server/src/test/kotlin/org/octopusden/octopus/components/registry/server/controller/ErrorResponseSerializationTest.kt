package org.octopusden.octopus.components.registry.server.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.ErrorCodes
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse

/**
 * Wire-compat guard for the `errorCode` field (CRS #358): a NULL code must be
 * OMITTED from the serialized body, not emitted as `"errorCode": null` — the
 * [1.7]/[1.8] compat oracles byte-diff v1–v3 ERROR bodies against the 2.0.87
 * baseline, and an always-present null key produced 2 691 KEY_MISSING_BASELINE
 * divergences (build 2.0.88-3928, every error-returning case in the smoke set).
 * Responses that DO carry a code (v4 conflict handlers) keep emitting it.
 */
class ErrorResponseSerializationTest {
    private val mapper = ObjectMapper()

    @Test
    @DisplayName("null errorCode is omitted from the JSON body (baseline byte-parity)")
    fun nullErrorCodeOmitted() {
        val json = mapper.writeValueAsString(ErrorResponse("Component 'x' is not found"))
        assertFalse(json.contains("errorCode"), json)
        assertEquals("""{"errorMessage":"Component 'x' is not found"}""", json)
    }

    @Test
    @DisplayName("a set errorCode is emitted")
    fun presentErrorCodeEmitted() {
        val json = mapper.writeValueAsString(
            ErrorResponse("uniqueness violation: …", ErrorCodes.UNIQUENESS_VIOLATION),
        )
        assertTrue(json.contains(""""errorCode":"UNIQUENESS_VIOLATION""""), json)
    }
}
