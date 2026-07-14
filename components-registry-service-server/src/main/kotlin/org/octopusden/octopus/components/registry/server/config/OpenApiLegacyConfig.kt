package org.octopusden.octopus.components.registry.server.config

import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI groups for the legacy **v1 / v2 / v3** read contracts.
 *
 * These are the stable Feign-client contracts consumed by other services; their
 * controllers are live on this server (`/rest/api/{1,2,3}/…`) but were missing
 * from the swagger-ui group dropdown because [OpenApiV4Config] defined only the
 * `v4` group, and a `GroupedOpenApi` present suppresses the default aggregated
 * doc from the UI. Exposing one group per legacy major restores them in swagger-ui
 * (`GET /v3/api-docs/{v1,v2,v3}`), which [WebSecurityConfig] already permits
 * anonymously via the `/v3/api-docs/` group-doc prefix.
 *
 * Unlike `v4`, these groups are NOT drift-gated (no committed spec / OpenApiV4SpecTest
 * equivalent) — they mirror whatever the legacy controllers expose. `info.version`
 * is the constant API-major string, not the build version.
 */
@Configuration
class OpenApiLegacyConfig {
    private fun legacyGroup(major: Int): GroupedOpenApi =
        GroupedOpenApi
            .builder()
            .group("v$major")
            .pathsToMatch("/rest/api/$major/**")
            .addOpenApiCustomizer { openApi ->
                openApi.info(
                    Info()
                        .title("Components Registry v$major API")
                        .version(major.toString())
                        .description(
                            "Legacy v$major read contract (Feign client). Served for reference; " +
                                "the Portal binds to v4.",
                        ),
                )
            }.build()

    @Bean
    fun v1OpenApiGroup(): GroupedOpenApi = legacyGroup(1)

    @Bean
    fun v2OpenApiGroup(): GroupedOpenApi = legacyGroup(2)

    @Bean
    fun v3OpenApiGroup(): GroupedOpenApi = legacyGroup(3)
}
