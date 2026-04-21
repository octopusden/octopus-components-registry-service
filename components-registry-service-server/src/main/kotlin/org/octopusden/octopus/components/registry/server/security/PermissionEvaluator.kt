package org.octopusden.octopus.components.registry.server.security

import org.octopusden.cloud.commons.security.BasePermissionEvaluator
import org.octopusden.cloud.commons.security.SecurityService
import org.springframework.stereotype.Component

@Component
class PermissionEvaluator(
    securityService: SecurityService,
) : BasePermissionEvaluator(securityService) {
    /**
     * Edit gate for a specific component. Param is the path variable verbatim —
     * today callers pass either a component name (`String`) or a UUID via `#id.toString()`
     * because v4 controllers mix both path schemes. Until component-level ownership
     * (ADR-004, Phase 2) lands, this reduces to the `EDIT_COMPONENTS` permission check.
     */
    @Suppress("UnusedParameter")
    fun canEditComponent(componentIdOrName: String): Boolean = hasPermission(EDIT_COMPONENTS)

    @Suppress("UnusedParameter")
    fun canDeleteComponent(componentIdOrName: String): Boolean = hasPermission(DELETE_COMPONENTS)

    fun canImport(): Boolean = hasPermission(IMPORT_DATA)

    companion object {
        const val ACCESS_COMPONENTS = "ACCESS_COMPONENTS"
        const val EDIT_COMPONENTS = "EDIT_COMPONENTS"
        const val DELETE_COMPONENTS = "DELETE_COMPONENTS"
        const val IMPORT_DATA = "IMPORT_DATA"
        const val ACCESS_AUDIT = "ACCESS_AUDIT"
    }
}
