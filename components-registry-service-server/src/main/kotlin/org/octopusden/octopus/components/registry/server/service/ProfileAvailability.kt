package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ComponentTemplate

/**
 * Whether the current user may use a profile or a template, and override a template's fields
 * (Decisions 6, 12). The listing, the create check and the template endpoints all ask this one
 * rule, so a later restriction — Delivery & Support creating from templates only, without
 * overrides — changes one implementation.
 */
interface ProfileAvailability {
    fun evaluate(profile: ComponentProfile): Availability

    fun evaluate(template: ComponentTemplate): Availability

    fun mayOverride(template: ComponentTemplate): Boolean

    data class Availability(
        val usable: Boolean,
        val reason: String?,
    )
}
