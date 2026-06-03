package org.octopusden.octopus.components.registry.server.config

import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.impl.ClassicEmployeeServiceClient
import org.octopusden.employee.client.impl.EmployeeServiceClientParametersProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the optional [EmployeeServiceClient] bean used by the runtime
 * active-employee validation (see
 * [org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService]).
 *
 * **Two-gate registration** (mirrors [FaultInjectionConfig] / the inert-URL
 * guard on the TeamCity client):
 *   1. `@ConditionalOnProperty("employee-service.enabled", havingValue="true")`
 *      — the @Bean factory is only considered when the flag is on; and
 *   2. a non-blank `url` guard inside the factory — a half-configured env
 *      (`enabled=true` but no URL) returns `null` so no client is wired.
 *
 * When no client bean is registered, `EmployeeDirectoryService`'s
 * `ObjectProvider<EmployeeServiceClient>` resolves empty and the active-employee
 * check degrades to DISABLED (fail-open). The required/pattern checks are
 * independent of this bean and always run.
 *
 * The client is built from an inline [EmployeeServiceClientParametersProvider]
 * with the same shape as the legacy CI validation task's
 * `getEmployeeServiceClient()`.
 */
@Configuration
class EmployeeServiceConfig {
    @Bean
    @ConditionalOnProperty("employee-service.enabled", havingValue = "true")
    fun employeeServiceClient(properties: EmployeeServiceProperties): EmployeeServiceClient? {
        if (properties.url.isBlank()) {
            log.warn(
                "employee-service.enabled=true but employee-service.url is blank — " +
                    "no EmployeeServiceClient bean wired; active-employee validation stays DISABLED (fail-open). " +
                    "Set employee-service.url in service-config to enable it.",
            )
            return null
        }
        log.info("Wiring EmployeeServiceClient against {}", properties.url)
        return ClassicEmployeeServiceClient(
            object : EmployeeServiceClientParametersProvider {
                override fun getApiUrl(): String = properties.url
                override fun getTimeRetryInMillis(): Int = properties.retryTimeMillis
                override fun getBearerToken(): String? = properties.token
                override fun getBasicCredentials(): String? = "${properties.username}:${properties.password}"
            },
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(EmployeeServiceConfig::class.java)
    }
}
