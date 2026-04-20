package org.octopusden.octopus.components.registry.server.config

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.cloud.commons.security.config.CloudCommonWebSecurityConfig
import org.octopusden.cloud.commons.security.config.SecurityProperties
import org.octopusden.cloud.commons.security.converter.UserInfoGrantedAuthoritiesConverter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain

@Configuration
@Import(AuthServerClient::class)
@EnableConfigurationProperties(SecurityProperties::class)
class WebSecurityConfig(
    private val authServerClient: AuthServerClient,
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
                        "/v2/api-docs",
                        "/v3/api-docs",
                        "/v3/api-docs/swagger-config",
                        "/swagger-resources/**",
                        "/swagger-ui/**",
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
}
