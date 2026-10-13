package org.octopusden.octopus.components.registry.server.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.support.designExampleProperties

class PermissionProfileAvailabilityTest {
    private val profile = ComponentProfileCatalog { designExampleProperties() }.profiles().first()

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
}
