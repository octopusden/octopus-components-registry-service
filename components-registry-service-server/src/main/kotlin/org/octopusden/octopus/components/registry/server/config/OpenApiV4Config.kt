package org.octopusden.octopus.components.registry.server.config

import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI generation for the **v4** API surface (TD-003 / [ADR-012]).
 *
 * v4 is the CRUD + audit + admin contract the Portal SPA binds to; v1/v2/v3 are stable
 * Feign-client read contracts and are deliberately NOT re-derived here. springdoc serves
 * this group at `GET /v3/api-docs/v4` (already permitted anonymously by
 * [WebSecurityConfig] / [AnonymousSecurityConfig]). `OpenApiV4SpecTest` captures that
 * document into `build/openapi/v4.json` and gates drift against the committed
 * `src/main/resources/openapi/v4.json`; `./gradlew generateOpenApiDocs` refreshes it.
 *
 * `info.version` is a CONSTANT API-major string (`"4"`), NOT the build/project version:
 * the committed spec must only change when the v4 surface changes, otherwise every release
 * version bump would churn the spec and fire the drift gate on no real API change.
 */
@Configuration
class OpenApiV4Config {
    @Bean
    fun v4OpenApiGroup(): GroupedOpenApi =
        GroupedOpenApi
            .builder()
            .group("v4")
            .pathsToMatch(
                "/rest/api/4/components/**",
                // AuditControllerV4 is mapped at /rest/api/4/audit (NOT /audit-log).
                "/rest/api/4/audit/**",
                // Covers AdminControllerV4, ConfigControllerV4's PUTs, ServiceEvent + Feedback
                // admin controllers — everything under /admin/**.
                "/rest/api/4/admin/**",
                // FeedbackControllerV4 submit endpoint (the admin side is under /admin/** above).
                "/rest/api/4/feedback/**",
                // ConfigControllerV4 GETs under /config/**.
                "/rest/api/4/config/**",
                // HealthControllerV4 — registry health statistics (SYS-057).
                "/rest/api/4/health/**",
                // InfoControllerV4 (not DB-gated).
                "/rest/api/4/info",
                // MigrationStatusControllerV4 — anonymous migration-activity probe (SYS-055).
                "/rest/api/4/migration-status",
                // VersionsControllerV4 — stateless version-format preview (SYS-059).
                "/rest/api/4/versions/**",
                // AuthController at /auth/me — outside /rest/api/4.
                "/auth/**",
            )
            // No pathsToExclude: with pathsToMatch this tight the v1/v2/v3 trees never match.
            // Scope the info to THIS group only (a global OpenAPI bean would also relabel the
            // ungrouped /v3/api-docs, which still covers v1/v2/v3). `info.version` is the constant
            // API-major string "4" — see the class kdoc.
            .addOpenApiCustomizer { openApi ->
                openApi.info(
                    Info()
                        .title("Components Registry v4 API")
                        .version("4")
                        .description(
                            "CRUD + audit + admin API consumed by the components-management Portal SPA. " +
                                "Generated from the v4 controllers (TD-003); v1/v2/v3 are separate stable read contracts.",
                        ),
                )
            }.build()
}
