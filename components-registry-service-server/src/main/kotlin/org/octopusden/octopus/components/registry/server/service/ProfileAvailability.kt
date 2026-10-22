package org.octopusden.octopus.components.registry.server.service

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.model.ComponentTemplate

/**
 * Whether the current user may use a profile or a template, and override a template's fields
 * (profiles Decision 6, templates Decision 12). The listing, the create check, the template
 * endpoints and the administrator read all ask this one rule, so who may create from templates
 * only, and without overrides, is decided in one implementation.
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
