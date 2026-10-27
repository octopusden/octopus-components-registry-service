package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator.Companion.CREATE_COMPONENTS
import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator.Companion.CREATE_FROM_TEMPLATE
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability

/**
 * Availability by create permission (template-only creators Decision 3). [CREATE_COMPONENTS]
 * allows every profile, every template and overrides; [CREATE_FROM_TEMPLATE] alone allows
 * templates only, without overrides. [CREATE_COMPONENTS] is checked first, so a user holding both
 * keeps every right.
 */
class PermissionProfileAvailability(
    private val hasPermission: (String) -> Boolean,
) : ProfileAvailability {
    override fun evaluate(profile: ComponentProfile): ProfileAvailability.Availability =
        when {
            hasPermission(CREATE_COMPONENTS) -> USABLE
            hasPermission(CREATE_FROM_TEMPLATE) -> TEMPLATES_ONLY
            else -> NO_PERMISSION
        }

    override fun evaluate(template: ComponentTemplate): ProfileAvailability.Availability =
        when {
            hasPermission(CREATE_COMPONENTS) || hasPermission(CREATE_FROM_TEMPLATE) -> USABLE
            else -> NO_PERMISSION
        }

    override fun mayOverride(template: ComponentTemplate): Boolean = hasPermission(CREATE_COMPONENTS)

    private companion object {
        val USABLE = ProfileAvailability.Availability(usable = true, reason = null)
        val TEMPLATES_ONLY = ProfileAvailability.Availability(usable = false, reason = "You may create components from templates only")
        val NO_PERMISSION = ProfileAvailability.Availability(usable = false, reason = "You do not have permission to create components")
    }
}
