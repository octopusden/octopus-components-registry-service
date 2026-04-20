package org.octopusden.octopus.components.registry.server.support

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.RequestPostProcessor

/**
 * JWT post-processors for MockMvc tests. Each helper creates a pre-built
 * JwtAuthenticationToken that bypasses the UserInfoGrantedAuthoritiesConverter,
 * so /userinfo does not need to be mocked when @MockBean AuthServerClient is in place.
 *
 * Role names match the octopus-security.roles keys in application-common.yml.
 */

fun adminJwt(): RequestPostProcessor =
    jwt()
        .jwt { it.claim("preferred_username", "alice") }
        .authorities(SimpleGrantedAuthority("ROLE_F1_ADMIN"))

fun editorJwt(): RequestPostProcessor =
    jwt()
        .jwt { it.claim("preferred_username", "bob") }
        .authorities(SimpleGrantedAuthority("ROLE_REGISTRY_EDITOR"))

fun viewerJwt(): RequestPostProcessor =
    jwt()
        .jwt { it.claim("preferred_username", "carol") }
        .authorities(SimpleGrantedAuthority("ROLE_REGISTRY_VIEWER"))
