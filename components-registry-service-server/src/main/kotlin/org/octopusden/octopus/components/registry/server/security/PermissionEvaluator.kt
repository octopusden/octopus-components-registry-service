package org.octopusden.octopus.components.registry.server.security

import org.octopusden.cloud.commons.security.BasePermissionEvaluator
import org.octopusden.cloud.commons.security.SecurityService
import org.springframework.stereotype.Component

@Component
class PermissionEvaluator(
    securityService: SecurityService,
) : BasePermissionEvaluator(securityService) {
    @Suppress("UnusedParameter")
    fun canEditComponent(componentName: String): Boolean = hasPermission(EDIT_COMPONENTS)

    @Suppress("UnusedParameter")
    fun canDeleteComponent(componentName: String): Boolean = hasPermission(DELETE_COMPONENTS)

    fun canImport(): Boolean = hasPermission(IMPORT_DATA)

    companion object {
        const val ACCESS_COMPONENTS = "ACCESS_COMPONENTS"
        const val EDIT_COMPONENTS = "EDIT_COMPONENTS"
        const val DELETE_COMPONENTS = "DELETE_COMPONENTS"
        const val IMPORT_DATA = "IMPORT_DATA"
        const val ACCESS_AUDIT = "ACCESS_AUDIT"
    }
}
