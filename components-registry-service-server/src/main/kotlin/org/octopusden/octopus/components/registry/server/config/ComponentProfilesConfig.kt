package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.security.PermissionEvaluator
import org.octopusden.octopus.components.registry.server.service.ProfileAvailability
import org.octopusden.octopus.components.registry.server.service.impl.ComponentProfileCatalog
import org.octopusden.octopus.components.registry.server.service.impl.PermissionProfileAvailability
import org.octopusden.octopus.components.registry.server.service.impl.ProfileCreateCheck
import org.octopusden.octopus.components.registry.server.template.ComponentDefaultsSeed
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

    /** `component-defaults` is read on every load, so a reload sees it after the rebind that precedes it. */
    @Bean
    fun componentProfileCatalog(
        source: ComponentProfilesSource,
        adminConfig: AdminConfigProperties,
    ): ComponentProfileCatalog = ComponentProfileCatalog(source::read) { ComponentDefaultsSeed.from(adminConfig.componentDefaults) }

    @Bean
    fun profileAvailability(permissionEvaluator: PermissionEvaluator): ProfileAvailability =
        PermissionProfileAvailability(permissionEvaluator::hasPermission)

    @Bean
    fun profileCreateCheck(
        catalog: ComponentProfileCatalog,
        availability: ProfileAvailability,
    ): ProfileCreateCheck = ProfileCreateCheck(catalog, availability)
}
