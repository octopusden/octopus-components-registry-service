package org.octopusden.octopus.components.registry.server.service.impl

import org.octopusden.octopus.components.registry.server.model.ComponentProfile
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Checks a create that names a profile (Decision 7): the profile is live, the user may use it,
 * the stored classification matches it, and every field rule holds. Each read takes the profiles
 * in use at call time, the same ones the listing returns.
 *
 * Unknown profile, classification and rule failures throw [IllegalArgumentException] (400); an
 * unusable profile throws a 403 [ResponseStatusException]. Messages start with the request path
 * they concern, like the other create errors.
 */
class ProfileCreateCheck(
    private val catalog: ComponentProfileCatalog,
    private val availability: ProfileAvailability,
) {
    fun check(
        profileId: String,
        component: CreatedComponent,
    ) {
        val profile =
            requireNotNull(catalog.profiles().firstOrNull { it.id == profileId }) { "profile: unknown profile '$profileId'" }
        val availability = availability.evaluate(profile)
        if (!availability.usable) throw ResponseStatusException(HttpStatus.FORBIDDEN, availability.reason)
        checkClassification(profile, component)
        profile.rules.forEach { rule ->
            require(rule.regex.matches(component.valueAt(rule.path).orEmpty())) { "${rule.path}: ${rule.message}" }
        }
    }

    private fun checkClassification(
        profile: ComponentProfile,
        component: CreatedComponent,
    ) {
        val expected = profile.classification
        requireFlag(profile.id, "solution", expected.solution, component.solution)
        requireFlag(profile.id, "external", expected.external, component.distributionExternal)
        if (expected.explicit != ComponentProfile.Explicit.ASK) {
            requireFlag(profile.id, "explicit", expected.explicit == ComponentProfile.Explicit.TRUE, component.distributionExplicit)
        }
    }

    private fun requireFlag(
        profileId: String,
        flag: String,
        expected: Boolean,
        stored: Boolean,
    ) = require(expected == stored) { "profile: profile '$profileId' needs $flag: $expected, but the create would store $flag: $stored" }
}
