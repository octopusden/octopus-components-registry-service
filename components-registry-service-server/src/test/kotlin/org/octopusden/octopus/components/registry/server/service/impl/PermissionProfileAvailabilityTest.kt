package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.support.designExampleProperties
import org.octopusden.octopus.components.registry.server.template.standaloneTemplateProperties

class PermissionProfileAvailabilityTest {
    private val catalog = ComponentProfileCatalog { designExampleProperties() + standaloneTemplateProperties() }
    private val profile = catalog.profiles().first()
    private val template = catalog.templates().single()

    @Test
    @DisplayName("Decision 6: a user with CREATE_COMPONENTS may use a profile")
    fun withCreatePermission() {
        val availability = PermissionProfileAvailability { it == "CREATE_COMPONENTS" }

        assertEquals(ProfileAvailability.Availability(usable = true, reason = null), availability.evaluate(profile))
    }

    @Test
    @DisplayName("Decision 6: a user without CREATE_COMPONENTS may not, with the reason")
    fun withoutCreatePermission() {
        val availability = PermissionProfileAvailability { it == "ACCESS_COMPONENTS" }

        assertEquals(
            ProfileAvailability.Availability(usable = false, reason = "You do not have permission to create components"),
            availability.evaluate(profile),
        )
    }

    @Test
    @DisplayName("Decision 12: a template is usable with CREATE_COMPONENTS, and then may be overridden")
    fun templateWithCreatePermission() {
        val availability = PermissionProfileAvailability { it == "CREATE_COMPONENTS" }

        assertEquals(ProfileAvailability.Availability(usable = true, reason = null), availability.evaluate(template))
        assertEquals(true, availability.mayOverride(template))
    }

    @Test
    @DisplayName("Decision 12: without CREATE_COMPONENTS a template is unusable, with the reason, and may not be overridden")
    fun templateWithoutCreatePermission() {
        val availability = PermissionProfileAvailability { it == "ACCESS_COMPONENTS" }

        assertEquals(
            ProfileAvailability.Availability(usable = false, reason = "You do not have permission to create components"),
            availability.evaluate(template),
        )
        assertEquals(false, availability.mayOverride(template))
    }
}
