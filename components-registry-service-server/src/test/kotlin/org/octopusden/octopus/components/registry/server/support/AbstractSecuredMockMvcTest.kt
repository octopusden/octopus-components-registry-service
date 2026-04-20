package org.octopusden.octopus.components.registry.server.support

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean

/**
 * Base class for @SpringBootTest classes that need:
 *  - MockMvc auto-configured,
 *  - AuthServerClient replaced with a Mockito mock so its eager OpenID discovery
 *    in init{} does not run (which would require a reachable Keycloak at context load).
 *
 * The @MockBean registers a BeanDefinition override before context refresh, preventing
 * the real @Component AuthServerClient from being instantiated.
 *
 * Security role mappings (octopus-security.roles) live in application-common.yml and are
 * loaded automatically when the `common` profile is active (which all 15 test classes use).
 */
@AutoConfigureMockMvc
abstract class AbstractSecuredMockMvcTest {
    @MockBean
    protected lateinit var authServerClient: AuthServerClient
}
