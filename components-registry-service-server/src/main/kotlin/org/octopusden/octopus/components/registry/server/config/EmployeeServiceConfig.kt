package org.octopusden.octopus.components.registry.server.config

import org.octopusden.employee.client.EmployeeServiceClient
import org.octopusden.employee.client.impl.ClassicEmployeeServiceClient
import org.octopusden.employee.client.impl.EmployeeServiceClientParametersProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Registers the optional [EmployeeServiceClient] bean used by the runtime
 * active-employee validation (see
 * [org.octopusden.octopus.components.registry.server.service.impl.EmployeeDirectoryService]).
 *
 * **Two-gate registration** (mirrors [FaultInjectionConfig] / the inert-URL
 * guard on the TeamCity client):
 *   1. `@ConditionalOnProperty("employee-service.enabled", havingValue="true")`
 *      — the @Bean factory is only considered when the flag is on; and
 *   2. [EmployeeServiceUrlConfiguredCondition] — a half-configured env
 *      (`enabled=true` but no URL) does not register the client bean.
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
    @Conditional(EmployeeServiceUrlConfiguredCondition::class)
    fun employeeServiceClient(properties: EmployeeServiceProperties): EmployeeServiceClient {
        log.info("Wiring EmployeeServiceClient against {}", properties.url)
        return ClassicEmployeeServiceClient(
            object : EmployeeServiceClientParametersProvider {
                override fun getApiUrl(): String = properties.url
                override fun getTimeRetryInMillis(): Int = properties.retryTimeMillis
                override fun getBearerToken(): String? = properties.token.takeIf { it.isNotBlank() }
                override fun getBasicCredentials(): String? =
                    if (properties.username.isNotBlank() && properties.password.isNotBlank()) {
                        "${properties.username}:${properties.password}"
                    } else {
                        null
                    }
            },
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(EmployeeServiceConfig::class.java)
    }
}

class EmployeeServiceUrlConfiguredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = !context.environment.getProperty("employee-service.url").isNullOrBlank()
}
