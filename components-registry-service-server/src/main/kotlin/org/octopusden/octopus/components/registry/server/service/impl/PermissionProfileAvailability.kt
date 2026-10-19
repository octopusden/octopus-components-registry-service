package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ComponentTemplate
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability

/** Usable for a user who may create components; such a user may also override any template field. */
class PermissionProfileAvailability(
    private val hasPermission: (String) -> Boolean,
) : ProfileAvailability {
    override fun evaluate(profile: ComponentProfile): ProfileAvailability.Availability = byPermission()

    override fun evaluate(template: ComponentTemplate): ProfileAvailability.Availability = byPermission()

    override fun mayOverride(template: ComponentTemplate): Boolean = evaluate(template).usable

    private fun byPermission() =
        if (hasPermission(CREATE_COMPONENTS)) {
            ProfileAvailability.Availability(usable = true, reason = null)
        } else {
            ProfileAvailability.Availability(usable = false, reason = "You do not have permission to create components")
        }

    private companion object {
        const val CREATE_COMPONENTS = "CREATE_COMPONENTS"
    }
}
