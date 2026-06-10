package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.impl.IntegrationHealth

/**
 * Wire shape of the employee-service integration health endpoint. Always
 * delivered with HTTP 200 — the status lives in the body so the Portal can
 * distinguish "integration is DOWN" from "request itself failed".
 */
data class EmployeeIntegrationHealthResponse(
    val status: IntegrationHealth,
)
