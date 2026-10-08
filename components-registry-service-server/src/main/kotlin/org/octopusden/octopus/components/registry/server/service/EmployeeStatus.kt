package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.service.impl.ActiveStatus

/** Whether a login is an active employee, as today's create asks the employee service. */
fun interface EmployeeStatus {
    fun of(login: String): ActiveStatus
}
