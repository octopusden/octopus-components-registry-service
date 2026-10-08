package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile

/**
 * Whether the current user may use a profile (Decision 6). The listing and the create check both
 * ask this one rule, so a later restriction — Delivery & Support creating from templates only —
 * changes one implementation.
 */
fun interface ProfileAvailability {
    fun evaluate(profile: ComponentProfile): Availability

    data class Availability(
        val usable: Boolean,
        val reason: String?,
    )
}

/** Usable for a user who may create components. */
class PermissionProfileAvailability(
    private val hasPermission: (String) -> Boolean,
) : ProfileAvailability {
    override fun evaluate(profile: ComponentProfile): ProfileAvailability.Availability =
        if (hasPermission(CREATE_COMPONENTS)) {
            ProfileAvailability.Availability(usable = true, reason = null)
        } else {
            ProfileAvailability.Availability(usable = false, reason = "You do not have permission to create components")
        }

    private companion object {
        const val CREATE_COMPONENTS = "CREATE_COMPONENTS"
    }
}
