package org.octopusden.octopus.components.registry.server.profile

import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource

/**
 * Reads the profile subtree as the flattened `key → value` map [ComponentProfileParser] expects,
 * keys relative to [PREFIX].
 *
 * Property names are taken as written rather than bound through `Binder`: binding would split a
 * dotted rule path such as `baseConfiguration.jira.projectKey` into nested maps and treat `[0]`
 * as a list index. Every enumerable property source contributes its names; the value is the one
 * the environment resolves, so a later profile file overrides the base key by key, as Spring
 * merges maps. Only the canonical kebab-case prefix is read — environment-variable forms are not.
 */
class ComponentProfilesSource(
    private val environment: ConfigurableEnvironment,
) {
    fun read(): Map<String, String> =
        environment.propertySources
            .asSequence()
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { it.propertyNames.asSequence() }
            .filter { it.startsWith(PREFIX) }
            .distinct()
            .associate { it.removePrefix(PREFIX) to environment.getProperty(it).orEmpty() }

    companion object {
        const val PREFIX = "components-registry.component-profiles."
    }
}
