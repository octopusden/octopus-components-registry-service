package org.octopusden.octopus.components.registry.server.config

import org.octopusden.octopus.components.registry.server.util.ComponentProfileParser
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource

/**
 * Reads the profile subtree as the flattened `key → value` map [ComponentProfileParser] expects,
 * keys relative to [PREFIX].
 *
 * Property names are taken as written rather than bound through `Binder`: binding would split a
 * dotted rule path such as `baseConfiguration.jira.projectKey` into nested maps and treat `[0]`
 * as a list index. Every enumerable property source contributes its names, in precedence order;
 * each value comes from the first source that holds that exact name, so a later profile file
 * overrides the base key by key, as Spring merges maps. Values are not looked up through the
 * environment: Boot's relaxed lookup treats `regular-internal` and `regularinternal` as one name
 * and would hand both ids the same values. Placeholders in a value are resolved; an unresolvable
 * one throws. Only the canonical kebab-case prefix is read — environment-variable forms are not.
 */
class ComponentProfilesSource(
    private val environment: ConfigurableEnvironment,
) {
    fun read(): Map<String, String> {
        val values = linkedMapOf<String, String>()
        environment.propertySources
            .filterIsInstance<EnumerablePropertySource<*>>()
            .forEach { source ->
                source.propertyNames
                    .filter { it.startsWith(PREFIX) }
                    .forEach { name -> values.putIfAbsent(name.removePrefix(PREFIX), source.getProperty(name)?.toString().orEmpty()) }
            }
        return values.mapValues { (_, value) -> environment.resolveRequiredPlaceholders(value) }
    }

    companion object {
        const val PREFIX = "components-registry.component-profiles."
    }
}
