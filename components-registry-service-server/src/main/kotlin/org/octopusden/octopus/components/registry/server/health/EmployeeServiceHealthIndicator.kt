package org.octopusden.octopus.components.registry.server.health

import org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService
import org.octopusden.octopus.components.registry.server.service.impl.IntegrationHealth
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Actuator surface of [EmployeeDirectoryService.probeHealth]: component
 * `employeeService` under `/actuator/health`, surfacing the probe's failure
 * detail (the real downstream cause) in the `reason`.
 *
 * Deployment safety: the OKD probes hit `/actuator/health/liveness` (the
 * liveness GROUP), and Spring only places livenessState/readinessState in the
 * probe groups by default — a custom indicator is never consulted there. So a
 * DOWN employee-service flips the aggregate `/actuator/health` (HTTP 503, for
 * monitoring and the Portal-side indicator) without restarting pods or
 * blocking a rollout. Eureka health propagation is off, so registry routing is
 * unaffected too.
 *
 * DISABLED (no client wired) is intentional configuration, not a failure —
 * reported as UNKNOWN with `enabled: false` so a dev stand without
 * employee-service stays green.
 *
 * Each health() call fires one real lookup (actuator does not cache by
 * default) — fine for scrape-style consumers; if the scrape interval ever
 * drops below a few seconds, configure the health endpoint's time-to-live.
 */
@Component("employeeService")
class EmployeeServiceHealthIndicator(
    private val employeeDirectory: EmployeeDirectoryService,
) : HealthIndicator {
    override fun health(): Health {
        val result = employeeDirectory.probeHealth()
        return when (result.health) {
            IntegrationHealth.UP -> Health.up().build()
            IntegrationHealth.DOWN ->
                Health
                    .down()
                    .withDetail(
                        "reason",
                        // Carry the real downstream cause (e.g. "...403 Forbidden") so the
                        // aggregate /actuator/health and the Portal banner name access-denied
                        // vs unreachable; fall back to the generic hint when none was captured.
                        result.detail?.let { "employee-service lookup failed — $it" } ?: GENERIC_REASON,
                    ).build()
            IntegrationHealth.DISABLED -> Health.unknown().withDetail("enabled", false).build()
        }
    }

    private companion object {
        private const val GENERIC_REASON =
            "employee-service lookup failed (credentials / gateway route / directory backend — see logs)"
    }
}
