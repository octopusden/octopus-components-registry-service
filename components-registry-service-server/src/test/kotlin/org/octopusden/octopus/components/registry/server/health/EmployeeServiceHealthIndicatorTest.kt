package org.octopusden.octopus.components.registry.server.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.service.impl.IntegrationHealth
import org.octopusden.octopus.components.registry.server.service.impl.ProbeResult
import org.springframework.boot.actuate.health.Status

/**
 * Unit tests for [EmployeeServiceHealthIndicator] — the actuator surface of
 * [EmployeeDirectoryService.probe]. The indicator is intentionally NOT part of
 * the liveness/readiness groups (Spring only puts livenessState/readinessState
 * there by default), so a DOWN employee-service never blocks deployment; it
 * only flips the aggregate `/actuator/health` and its own component path.
 */
class EmployeeServiceHealthIndicatorTest {
    private fun indicatorWith(result: ProbeResult): EmployeeServiceHealthIndicator {
        val directory = mock(EmployeeDirectoryService::class.java)
        `when`(directory.probeHealth()).thenReturn(result)
        return EmployeeServiceHealthIndicator(directory)
    }

    @Test
    @DisplayName("probe UP → actuator UP")
    fun `up maps to UP`() {
        assertEquals(Status.UP, indicatorWith(ProbeResult(IntegrationHealth.UP, null)).health().status)
    }

    @Test
    @DisplayName("probe DOWN with a detail → actuator DOWN surfacing the real cause")
    fun `down surfaces the probe detail`() {
        val health = indicatorWith(ProbeResult(IntegrationHealth.DOWN, "Forbidden: 403 Forbidden")).health()
        assertEquals(Status.DOWN, health.status)
        val reason = health.details["reason"] as String
        assertTrue(reason.contains("403")) { "reason should carry the downstream status: $reason" }
        assertTrue(reason.contains("employee-service lookup failed")) { reason }
    }

    @Test
    @DisplayName("probe DOWN without a detail → actuator DOWN with the generic hint")
    fun `down falls back to the generic reason`() {
        val health = indicatorWith(ProbeResult(IntegrationHealth.DOWN, null)).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals(
            "employee-service lookup failed (credentials / gateway route / directory backend — see logs)",
            health.details["reason"],
        )
    }

    @Test
    @DisplayName("probe DISABLED → actuator UNKNOWN with enabled=false detail")
    fun `disabled maps to UNKNOWN`() {
        val health = indicatorWith(ProbeResult(IntegrationHealth.DISABLED, null)).health()
        assertEquals(Status.UNKNOWN, health.status)
        assertEquals(false, health.details["enabled"])
    }
}
