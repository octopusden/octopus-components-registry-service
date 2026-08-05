package org.octopusden.octopus.components.registry.server.service.rms

import org.octopusden.octopus.components.registry.server.config.RmsProperties
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
 * Registers the optional [RmsClient] bean — two-gates:
 *   1. `@ConditionalOnProperty("release-management-service.enabled", havingValue="true")`, and
 *   2. [RmsUrlConfiguredCondition] — `enabled=true` with a blank `url` still does not register the bean.
 * With no bean registered, callers resolving an `ObjectProvider<RmsClient>` see it empty and the
 * feature is off, not degraded.
 */
@Configuration
class RmsClientConfig {
    @Bean
    @ConditionalOnProperty("release-management-service.enabled", havingValue = "true")
    @Conditional(RmsUrlConfiguredCondition::class)
    fun rmsClient(properties: RmsProperties): RmsClient {
        log.info("Wiring RmsClient against {}", properties.url)
        return DefaultRmsClient(RestClient.builder().baseUrl(properties.url).build())
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RmsClientConfig::class.java)
    }
}

class RmsUrlConfiguredCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = !context.environment.getProperty("release-management-service.url").isNullOrBlank()
}
