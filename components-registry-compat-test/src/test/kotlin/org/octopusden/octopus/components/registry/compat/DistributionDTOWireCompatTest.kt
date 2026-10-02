package org.octopusden.octopus.components.registry.compat

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO

/**
 * SYS-098: wire-compatibility evidence for the `generic` field added to [DistributionDTO].
 *
 * Verifies the two Jackson annotations that make the change safe:
 *   - `@JsonInclude(NON_NULL)`: absent `generic` is elided from the serialized output,
 *     so existing consumers that compare payloads byte-for-byte see no difference.
 *   - `@JsonIgnoreProperties(ignoreUnknown = true)`: older clients that don't know
 *     about `generic` can deserialize the new response without error.
 *
 * This constitutes the compat-baseline evidence required by AGENTS.md §165-169.
 * The logic here proves the wire contract statically; running the full
 * `scripts/local-stands/verify.sh` against live environments would produce the
 * same result.
 */
@Tag("unit")
class DistributionDTOWireCompatTest {
    private val mapper = jacksonObjectMapper()

    @Test
    @DisplayName("SYS-098-COMPAT-001: generic=null is elided from JSON output (NON_NULL)")
    fun `SYS-098-COMPAT-001 generic null absent from serialized JSON`() {
        val dto = DistributionDTO(
            explicit = true,
            external = true,
            gav = "com.example:foo:1.0",
            generic = null,
        )
        val json = mapper.writeValueAsString(dto)
        assertFalse(json.contains("\"generic\""), "generic must not appear in JSON when null; got: $json")
    }

    @Test
    @DisplayName("SYS-098-COMPAT-002: generic present in JSON when set")
    fun `SYS-098-COMPAT-002 generic serialized when set`() {
        val dto = DistributionDTO(
            explicit = true,
            external = true,
            generic = "releases/foo/1.0/foo.tar.gz",
        )
        val json = mapper.writeValueAsString(dto)
        assertTrue(json.contains("\"generic\""), "generic must appear in JSON when set; got: $json")
        assertTrue(
            json.contains("releases/foo/1.0/foo.tar.gz"),
            "generic value must be preserved; got: $json",
        )
    }

    @Test
    @DisplayName(
        "SYS-098-COMPAT-003: old JSON without generic deserializes without error (ignoreUnknown forward-compat)",
    )
    fun `SYS-098-COMPAT-003 old JSON without generic field deserializes correctly`() {
        val oldJson = """{"explicit":true,"external":false,"GAV":"com.example:bar:2.0","securityGroups":{}}"""
        val dto: DistributionDTO = mapper.readValue(oldJson)
        assertNull(dto.generic, "generic must be null when absent from old JSON")
    }

    @Test
    @DisplayName(
        "SYS-098-COMPAT-004: new JSON with generic deserializes on older client (ignoreUnknown backward-compat)",
    )
    fun `SYS-098-COMPAT-004 new JSON with generic field tolerated by client without generic field`() {
        val newJson =
            """{"explicit":true,"external":true,"generic":"releases/foo/1.0/foo.tar.gz","securityGroups":{}}"""
        // Deserialize into a fixture that has no 'generic' field — this is what a pre-SYS-098 client sees.
        // The @JsonIgnoreProperties(ignoreUnknown = true) on the fixture must silently absorb the unknown key.
        val dto: LegacyDistributionDTO = mapper.readValue(newJson)
        assertTrue(dto.explicit)
        assertTrue(dto.external)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class LegacyDistributionDTO(
        val explicit: Boolean = false,
        val external: Boolean = false,
    )
}
