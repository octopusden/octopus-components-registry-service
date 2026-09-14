package org.octopusden.octopus.components.registry.server.mapper

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.core.dto.DistributionDTO
import org.octopusden.octopus.escrow.model.Distribution
import org.octopusden.octopus.escrow.model.SecurityGroups

class DistributionToDTOMapperTest {
    private val mapper = jacksonObjectMapper()

    @Test
    @DisplayName("SYS-094: generic value propagates from Distribution to DTO")
    fun `SYS-094 generic value propagates from Distribution to DTO`() {
        val distribution = Distribution(
            true,
            true,
            null,
            null,
            null,
            null,
            "releases/foo/1.0.0/foo.tar.gz",
            SecurityGroups(null),
        )

        val dto = distribution.toDTO()

        assertEquals("releases/foo/1.0.0/foo.tar.gz", dto.generic)
        assertNull(dto.gav)
        assertNull(dto.deb)
        assertNull(dto.rpm)
        assertNull(dto.docker)
    }

    @Test
    @DisplayName("SYS-094: Distribution without generic yields null generic in DTO")
    fun `SYS-094 Distribution without generic yields null generic in DTO`() {
        val distribution = Distribution(
            true,
            true,
            "g:a:jar",
            null,
            null,
            null,
            null,
            SecurityGroups(null),
        )

        val dto = distribution.toDTO()

        assertNull(dto.generic)
        assertEquals("g:a:jar", dto.gav)
    }

    @Test
    @DisplayName("SYS-094: payload without generic deserializes with generic = null")
    fun `SYS-094 payload without generic deserializes with generic null`() {
        val json = """{"explicit":true,"external":true,"GAV":"g:a:jar","securityGroups":{}}"""

        val dto = mapper.readValue(json, DistributionDTO::class.java)

        assertNull(dto.generic)
        assertEquals("g:a:jar", dto.gav)
    }

    @Test
    @DisplayName("SYS-094: payload with generic deserializes correctly on new server")
    fun `SYS-094 payload with generic deserializes correctly on new server`() {
        val json = """{"explicit":true,"external":true,"generic":"releases/foo/1.0.0/foo.tar.gz","securityGroups":{}}"""

        val dto = mapper.readValue(json, DistributionDTO::class.java)

        assertEquals("releases/foo/1.0.0/foo.tar.gz", dto.generic)
    }
}
