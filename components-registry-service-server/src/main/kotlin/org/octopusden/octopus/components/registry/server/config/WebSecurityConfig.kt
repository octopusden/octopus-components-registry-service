package org.octopusden.octopus.components.registry.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.cloud.commons.security.config.AuthServerProperties
import org.octopusden.cloud.commons.security.config.CloudCommonWebSecurityConfig
import org.octopusden.cloud.commons.security.config.SecurityProperties
import org.octopusden.cloud.commons.security.converter.UserInfoGrantedAuthoritiesConverter
import org.octopusden.octopus.components.registry.core.dto.ErrorResponse
import org.springframework.beans.factory.BeanInitializationException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

@Configuration
@Import(AuthServerClient::class)
@EnableConfigurationProperties(SecurityProperties::class)
class WebSecurityConfig(
    private val authServerClient: AuthServerClient,
    private val authServerProperties: AuthServerProperties,
    private val objectMapper: ObjectMapper,
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
                        // Only health probes are anonymous. Other actuator endpoints
                        // (env, metrics, heapdump, loggers, configprops) leak operational
                        // detail and must stay behind auth even if the deployed
                        // management.endpoints.web.exposure.include broadens.
                        "/actuator/health",
                        "/actuator/health/**",
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
                    // v4 read endpoints — public; @PreAuthorize checks ACCESS_COMPONENTS,
                    // which ROLE_ANONYMOUS is granted in the role-map, so unauthenticated
                    // requests pass method-security too.
                    .requestMatchers(HttpMethod.GET, "/rest/api/4/components/**", "/rest/api/4/config/**")
                    .permitAll()
                    // v4 writes + admin + audit — authenticated + method-level @PreAuthorize.
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
            }
            // Spring Security's ExceptionTranslationFilter intercepts AuthenticationException
            // and AccessDeniedException before they would ever hit @ControllerAdvice. Wire
            // entry-point and access-denied handlers HERE so 401/403 responses ship a JSON
            // ErrorResponse envelope consistent with the rest of the API. Without these,
            // Spring's defaults send back HTML / empty bodies and clients have to special-case
            // the security paths.
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint(jsonAuthenticationEntryPoint())
                ex.accessDeniedHandler(jsonAccessDeniedHandler())
            }
            .cors { it.disable() }
            // CSRF is intentionally disabled: registry is a stateless OAuth2 resource
            // server. There is no session cookie a CSRF attacker could ride — every
            // mutating request authenticates with a Bearer JWT presented in the
            // Authorization header, which a cross-site form post cannot set.
            // The portal BFF (octopus-components-management-portal) is the cookie-session
            // surface and enforces CSRF there with a double-submit cookie. CodeQL
            // "Disabled Spring CSRF protection" is acknowledged here.
            .csrf { it.disable() }
        return http.build()
    }

    @Bean
    fun jsonAuthenticationEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _: HttpServletRequest, response: HttpServletResponse, ex: AuthenticationException ->
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", ex.localizedMessage)
        }

    @Bean
    fun jsonAccessDeniedHandler(): AccessDeniedHandler =
        AccessDeniedHandler { _: HttpServletRequest, response: HttpServletResponse, ex: AccessDeniedException ->
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", ex.localizedMessage)
        }

    private fun writeJsonError(response: HttpServletResponse, status: Int, fallback: String, detail: String?) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(objectMapper.writeValueAsString(ErrorResponse(detail.takeUnless { it.isNullOrBlank() } ?: fallback)))
    }

    /**
     * Custom JwtDecoder: lazy JWKS fetch **plus** explicit issuer validation against
     * `auth-server.url`/`realm`.
     *
     * Startup tolerance to an unreachable Keycloak is **only partial**: this decoder does
     * not hit the network at bean init, but the cloud-commons `AuthServerClient` (imported
     * above) performs eager OIDC discovery in its own `init{}` block. So the pod still
     * fail-fasts if `auth-server.url` is blank or the `/.well-known/openid-configuration`
     * endpoint is unreachable at context refresh. That is intentional: we'd rather crash
     * at deploy time with a clear error than serve traffic while silently unable to
     * authenticate. Tests and the fat-jar smoke run stub discovery accordingly
     * (`@MockBean AuthServerClient` in unit tests, WireMock in `FatJarStartupIntegrationTest`).
     *
     * Why not use `spring.security.oauth2.resourceserver.jwt.issuer-uri`: it forces a second
     * OIDC discovery HTTP call at context refresh, doubling the startup attack surface.
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
