package org.octopusden.octopus.components.registry.server.service

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
