package org.octopusden.octopus.components.registry.server.dto.v4

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.rms.ComponentBuildRanges
import org.octopusden.octopus.components.registry.server.service.rms.RMSBuildParametersReport
import java.time.Duration
import java.time.Instant

class RMSSweepStatusResponseTest {
    @Test
    @DisplayName("disabled integration -> enabled=false, every other field at its empty/null default")
    fun `disabled integration reports empty status`() {
        val report = RMSBuildParametersReport(null, null, null, emptyMap(), emptySet())

        val response = RMSSweepStatusResponse.from(enabled = false, report = report)

        assertEquals(false, response.enabled)
        assertNull(response.generatedAt)
        assertNull(response.lastAttemptAt)
        assertNull(response.lastSweepDurationMillis)
        assertNull(response.refreshError)
        assertEquals(0, response.componentsWithData)
        assertEquals(emptyList<String>(), response.unavailableComponents)
    }

    @Test
    @DisplayName("enabled with data -> fields populated, unavailable components sorted")
    fun `enabled integration reports populated status`() {
        val now = Instant.now()
        val report =
            RMSBuildParametersReport(
                generatedAt = now,
                lastAttemptAt = now,
                refreshError = null,
                components = mapOf("comp-a" to ComponentBuildRanges(emptyList(), emptyList())),
                unavailableComponents = setOf("comp-z", "comp-a"),
                lastSweepDuration = Duration.ofMillis(1234),
            )

        val response = RMSSweepStatusResponse.from(enabled = true, report = report)

        assertEquals(true, response.enabled)
        assertEquals(now, response.generatedAt)
        assertEquals(now, response.lastAttemptAt)
        assertEquals(1234L, response.lastSweepDurationMillis)
        assertNull(response.refreshError)
        assertEquals(1, response.componentsWithData)
        assertEquals(listOf("comp-a", "comp-z"), response.unavailableComponents)
    }

    @Test
    @DisplayName("enabled but currently failing -> refreshError surfaced as-is")
    fun `refreshError is surfaced as-is`() {
        val report = RMSBuildParametersReport(null, Instant.now(), "SimulatedFailure", emptyMap(), emptySet())

        val response = RMSSweepStatusResponse.from(enabled = true, report = report)

        assertEquals("SimulatedFailure", response.refreshError)
    }
}
