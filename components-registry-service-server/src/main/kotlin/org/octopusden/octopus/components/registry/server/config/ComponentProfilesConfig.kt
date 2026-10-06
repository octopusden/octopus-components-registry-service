package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.profile.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.profile.ComponentProfilesSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Wires the profiles read from `components-registry.component-profiles`. Not a
 * `@ConfigurationProperties` bean: binding would rename ids, split dotted rule paths and drop
 * unknown keys (design Decision 1). Active in every mode, with or without a database.
 */
@Configuration
class ComponentProfilesConfig {
    @Bean
    fun componentProfilesSource(environment: ConfigurableEnvironment): ComponentProfilesSource = ComponentProfilesSource(environment)

    @Bean
    fun componentProfileCatalog(source: ComponentProfilesSource): ComponentProfileCatalog = ComponentProfileCatalog(source::read)
}
