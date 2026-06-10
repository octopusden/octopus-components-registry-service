package org.octopusden.octopus.components.registry.server.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.service.impl.IntegrationHealth
import org.springframework.boot.actuate.health.Status

/**
 * Unit tests for [EmployeeServiceHealthIndicator] — the actuator surface of
 * [EmployeeDirectoryService.probe]. The indicator is intentionally NOT part of
 * the liveness/readiness groups (Spring only puts livenessState/readinessState
 * there by default), so a DOWN employee-service never blocks deployment; it
 * only flips the aggregate `/actuator/health` and its own component path.
 */
class EmployeeServiceHealthIndicatorTest {
    private fun indicatorWith(health: IntegrationHealth): EmployeeServiceHealthIndicator {
        val directory = mock(EmployeeDirectoryService::class.java)
        `when`(directory.probe()).thenReturn(health)
        return EmployeeServiceHealthIndicator(directory)
    }

    @Test
    @DisplayName("probe UP → actuator UP")
    fun `up maps to UP`() {
        assertEquals(Status.UP, indicatorWith(IntegrationHealth.UP).health().status)
    }

    @Test
    @DisplayName("probe DOWN → actuator DOWN with a reason detail")
    fun `down maps to DOWN`() {
        val health = indicatorWith(IntegrationHealth.DOWN).health()
        assertEquals(Status.DOWN, health.status)
        assertEquals(
            "employee-service lookup failed (credentials / gateway route / directory backend — see logs)",
            health.details["reason"],
        )
    }

    @Test
    @DisplayName("probe DISABLED → actuator UNKNOWN with enabled=false detail")
    fun `disabled maps to UNKNOWN`() {
        val health = indicatorWith(IntegrationHealth.DISABLED).health()
        assertEquals(Status.UNKNOWN, health.status)
        assertEquals(false, health.details["enabled"])
    }
}
