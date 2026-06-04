package org.octopusden.octopus.components.registry.server.dto.v4

import org.octopusden.octopus.components.registry.server.service.impl.EmployeeMatch

/** Wire shape returned by the exact employee lookup endpoint. */
data class EmployeeMatchResponse(
    val username: String,
    val active: Boolean,
) {
    companion object {
        fun from(match: EmployeeMatch): EmployeeMatchResponse =
            EmployeeMatchResponse(
                username = match.username,
                active = match.active,
            )
    }
}
