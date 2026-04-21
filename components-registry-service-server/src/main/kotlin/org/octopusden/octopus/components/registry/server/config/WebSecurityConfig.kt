package org.octopusden.octopus.components.registry.server.config

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.cloud.commons.security.config.AuthServerProperties
import org.octopusden.cloud.commons.security.config.CloudCommonWebSecurityConfig
import org.octopusden.cloud.commons.security.config.SecurityProperties
import org.octopusden.cloud.commons.security.converter.UserInfoGrantedAuthoritiesConverter
import org.springframework.beans.factory.BeanInitializationException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

@Configuration
@Import(AuthServerClient::class)
@EnableConfigurationProperties(SecurityProperties::class)
class WebSecurityConfig(
    private val authServerClient: AuthServerClient,
    private val authServerProperties: AuthServerProperties,
    securityProperties: SecurityProperties,
) : CloudCommonWebSecurityConfig(authServerClient, securityProperties) {
    @Bean
    override fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/",
                        "/error",
                        "/actuator/**",
                        // springdoc-openapi + swagger-ui: cover the group docs,
                        // YAML variant, web-jars (swagger-ui assets) — otherwise
                        // docs render as 401 for web-jars stylesheets/JS.
                        "/v2/api-docs",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/v3/api-docs/swagger-config",
                        "/swagger-resources/**",
                        "/swagger-ui/**",
                        "/webjars/**",
                    ).permitAll()
                    // Legacy read-only APIs — public (Phase 1 from ADR-004).
                    .requestMatchers("/rest/api/1/**", "/rest/api/2/**", "/rest/api/3/**")
                    .permitAll()
                    // v4 — authenticated + method-level @PreAuthorize.
                    .requestMatchers("/rest/api/4/**")
                    .authenticated()
                    // /auth/me — must be logged in.
                    .requestMatchers("/auth/**")
                    .authenticated()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(
                        JwtAuthenticationConverter().apply {
                            setJwtGrantedAuthoritiesConverter(
                                UserInfoGrantedAuthoritiesConverter(authServerClient),
                            )
                        },
                    )
                }
            }.cors { it.disable() }
            .csrf { it.disable() }
        return http.build()
    }

    /**
     * Custom JwtDecoder: lazy JWKS fetch (so context init survives a temporarily unreachable
     * Keycloak) **plus** explicit issuer validation against `auth-server.url`/`realm`.
     *
     * Why not use `spring.security.oauth2.resourceserver.jwt.issuer-uri`:
     * that config forces an OIDC discovery HTTP call at context refresh, which would crash
     * startup if Keycloak is momentarily down and would also complicate `@SpringBootTest`.
     *
     * Why not rely on the auto-configured `jwk-set-uri`-only decoder: it validates the
     * signature and timestamps but does NOT enforce the `iss` claim, so the service would
     * accept any JWT signed by the configured JWK set regardless of issuer.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val url = authServerProperties.url
        val realm = authServerProperties.realm
        if (url.isNullOrBlank() || realm.isNullOrBlank()) {
            throw BeanInitializationException(
                "auth-server.url and auth-server.realm must both be set (got url='$url', realm='$realm')",
            )
        }
        // Normalize trailing slash — otherwise issuer becomes "http://host//realms/..."
        // and the JwtIssuerValidator string-compare silently rejects every token.
        val issuer = "${url.trimEnd('/')}/realms/$realm"
        val jwkSetUri = "$issuer/protocol/openid-connect/certs"
        val decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefault(),
                JwtIssuerValidator(issuer),
            ),
        )
        return decoder
    }
}
