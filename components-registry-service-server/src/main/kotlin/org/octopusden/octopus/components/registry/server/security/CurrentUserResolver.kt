package org.octopusden.octopus.components.registry.server.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Resolves the username for `audit_log.changed_by` from the active Spring Security context.
 *
 * Why this is its own helper rather than a one-line `securityService.getCurrentUser()` call:
 * the cloud-commons `SecurityService` resolves the user via the `UserInfoGrantedAuthoritiesConverter`
 * chain, which calls Keycloak's userinfo endpoint. That's the right path for fully-blown
 * production reads (granted roles + groups), but it's overkill for "give me the username"
 * and it does not work cleanly in MockMvc tests where `AuthServerClient` is `@MockBean`
 * (the JWT post-processors deliberately bypass the converter — see `SecurityTestHelpers`).
 *
 * This resolver reads directly from the JWT in the security context — `preferred_username`
 * first (Keycloak's canonical username claim), then `sub` as a fallback. If there is no
 * authenticated user (background jobs, async tasks running outside an HTTP context),
 * returns `"system"` so `changed_by` is never null in practice. Matches the contract
 * promised in `docs/db-migration/technical-design.md` §6.4 (Audit changedBy wiring).
 */
@Component
class CurrentUserResolver {
    fun currentUsername(): String {
        val auth = SecurityContextHolder.getContext().authentication
        return when {
            auth is JwtAuthenticationToken -> {
                auth.token.getClaimAsString("preferred_username")
                    ?: auth.name?.takeIf { it.isNotBlank() }
                    ?: SYSTEM_USER
            }
            auth?.name?.isNotBlank() == true -> auth.name
            else -> SYSTEM_USER
        }
    }

    companion object {
        const val SYSTEM_USER = "system"
    }
}
