package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.core.dto.ServiceMode
import org.octopusden.octopus.components.registry.server.model.ServiceStatus
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

@Configuration
@EnableConfigurationProperties(ComponentsRegistryProperties::class)
class ApplicationConfig(val componentsRegistryProperties: ComponentsRegistryProperties) {

    @Bean
    fun cacheStatus(): ServiceStatus {
        return ServiceStatus(
            ServiceMode.byVcsEnabled(componentsRegistryProperties.vcs.enabled),
            Date()
        )
    }

    @Bean(name = ["dependencyMapping"])
    fun dependencyMapping(): MutableMap<String, String> = ConcurrentHashMap<String, String>()
}
