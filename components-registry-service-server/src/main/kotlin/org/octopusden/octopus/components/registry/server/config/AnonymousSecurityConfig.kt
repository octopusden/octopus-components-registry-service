package org.octopusden.octopus.components.registry.server.config

import org.octopusden.cloud.commons.security.SecurityService
import org.octopusden.cloud.commons.security.config.SecurityProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * FT/dev fallback that loads when `auth-server.disabled=true`. Production must keep
 * the property unset — [WebSecurityConfig] then loads instead and preserves the
 * fail-fast contract on missing `auth-server.url` / `auth-server.realm`.
 *
 * This config re-registers two pieces that disappear together with [WebSecurityConfig]
 * because they live on its parent [org.octopusden.cloud.commons.security.config.CloudCommonWebSecurityConfig]:
 *
 *  1. The `SecurityService` `@Bean`. `PermissionEvaluator` and `AuthController` inject
 *     it unconditionally — without it the application context fails to refresh, and
 *     we'd just trade an OIDC-discovery crash for a missing-bean crash.
 *  2. `@EnableMethodSecurity`. Without it `@PreAuthorize` becomes a no-op and v4
 *     write handlers (e.g. `DELETE /rest/api/4/components/{id}`) would accept
 *     anonymous traffic. With it active, `ROLE_ANONYMOUS` carries `ACCESS_COMPONENTS`
 *     via the `octopus-security.roles` map and v4 reads pass; v4 writes return 403
 *     because `ROLE_ANONYMOUS` lacks `DELETE_COMPONENTS` / `EDIT_COMPONENTS`.
 *
 * The HTTP filter chain itself is permissive (`anyRequest().permitAll()`); method
 * security does the actual gating.
 */
@Configuration
@ConditionalOnProperty(name = ["auth-server.disabled"], havingValue = "true")
@EnableConfigurationProperties(SecurityProperties::class)
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
class AnonymousSecurityConfig(
    private val securityProperties: SecurityProperties,
) {
    @Bean
    fun securityService(): SecurityService = SecurityService(securityProperties)

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            // CSRF is intentionally disabled, mirroring WebSecurityConfig: the registry is a
            // stateless OAuth2 resource server, so there is no session cookie a CSRF attacker
            // could ride. AnonymousSecurityConfig only loads in FT/dev (auth-server.disabled=true)
            // and the same browser-form-post threat model still does not apply. CodeQL
            // "Disabled Spring CSRF protection" (java/spring-disabled-csrf-protection) is
            // acknowledged here.
            .csrf { it.disable() }
            .cors { it.disable() }
            .build()
}
