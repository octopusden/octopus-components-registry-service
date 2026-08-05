package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.RMSProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.web.client.RestClient

/**
 * Registers the optional [RMSClient] bean — two-gates:
 *   1. `@ConditionalOnProperty("release-management-service.enabled", havingValue="true")`, and
 *   2. [RMSUrlConfiguredCondition] — `enabled=true` with a blank `url` still does not register the bean.
 * With no bean registered, callers resolving an `ObjectProvider<RMSClient>` see it empty and the
 * feature is off, not degraded.
 */
@Configuration
class RMSClientConfig {
    @Bean
    @ConditionalOnProperty("release-management-service.enabled", havingValue = "true")
    @Conditional(RMSUrlConfiguredCondition::class)
    fun rmsClient(properties: RMSProperties): RMSClient {
        log.info("Wiring RMSClient against {}", properties.url)
        return DefaultRMSClient(RestClient.builder().baseUrl(properties.url).build())
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RMSClientConfig::class.java)
    }
}

class RMSUrlConfiguredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = !context.environment.getProperty("release-management-service.url").isNullOrBlank()
}
