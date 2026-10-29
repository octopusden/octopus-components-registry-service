package org.octopusden.octopus.components.registry.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the employee-service runtime active-employee check. Bound
 * from `employee-service.*` in application*.yml (defaults below).
 *
 * Defaults are intentionally inert: with `enabled` false (and `url` blank), the
 * [EmployeeServiceConfig] bean is not registered — so dev / unconfigured envs
 * boot cleanly without employee-service credentials, and the active-employee
 * check degrades to DISABLED (fail-open). Production wires real values through
 * `service-config` per env; the env var names mirror the build-time
 * `EMPLOYEE_SERVICE_URL` / `EMPLOYEE_SERVICE_TOKEN` already used by the legacy
 * CI validation task (`.teamcity/settings.kts`).
 *
 * Mirrors [org.octopusden.octopus.components.registry.server.teamcity.TeamcityProperties]
 * (inert-default + non-blank-url two-gate pattern).
 */
@ConfigurationProperties(prefix = "employee-service")
class EmployeeServiceProperties(
    /**
     * Master switch for the active-employee check. False by default so
     * dev/unconfigured envs don't attempt employee-service calls. When false the
     * client bean is not built and the check degrades to DISABLED (fail-open).
     */
    val enabled: Boolean = false,
    /**
     * Base URL of the employee-service API. Blank is treated as a
     * misconfiguration even when `enabled=true` — the bean's second gate skips
     * client construction so a half-configured env stays fail-open rather than
     * building a client that would throw on every call.
     */
    val url: String = "",
    /** Bearer token (preferred). When blank, basic credentials are used. */
    val token: String = "",
    /** Service-account username (HTTP Basic auth fallback when `token` is blank). */
    val username: String = "",
    /** Service-account password (HTTP Basic auth fallback when `token` is blank). */
    val password: String = "",
    /** Feign retry budget in milliseconds (mirrors the legacy task's 5000 ms). */
    val retryTimeMillis: Int = 5000,
    /**
     * Connect timeout in milliseconds. `EmployeeServiceClientParametersProvider`'s
     * default (300_000 ms) is unsafe on this bean: `canEditComponent` calls the
     * client synchronously inside `@PreAuthorize` and inside the per-response
     * `canEdit` stamping, on every detail GET — a degraded employee-service would
     * otherwise pin servlet threads for up to 5 minutes per request. Mirrors the
     * legacy CI validation task's override.
     */
    val connectTimeoutMillis: Int = 5000,
    /** Read timeout in milliseconds. See [connectTimeoutMillis] for why the library default is unsafe here. */
    val readTimeoutMillis: Int = 10000,
    /** Connection TTL in milliseconds. See [connectTimeoutMillis]. */
    val connectionTtlMillis: Int = 300000,
)
