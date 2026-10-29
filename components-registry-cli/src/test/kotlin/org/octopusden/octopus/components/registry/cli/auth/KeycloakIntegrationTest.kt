package org.octopusden.octopus.components.registry.cli.auth

import org.junit.jupiter.api.Disabled
import kotlin.test.Test

/**
 * Gated, end-to-end device-flow login against a real Keycloak issuer. Kept as a placeholder until the
 * public device-flow client exists; the single method stays [Disabled] so the suite is green meanwhile.
 */
class KeycloakIntegrationTest {
    @Test
    @Disabled("gated on Keycloak Part C spike: needs the public device-flow client components-registry-cli")
    fun deviceFlowLoginAgainstRealKeycloak() {
        // TODO(spike): drive login against a real Keycloak issuer once the public client exists
    }
}
