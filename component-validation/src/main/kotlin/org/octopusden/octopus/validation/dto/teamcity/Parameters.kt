package org.octopusden.octopus.validation.dto.teamcity

/**
 * A read-only bag of TeamCity parameters (project/config/step level, depending on where it was
 * built from). Values may still contain unresolved `%paramName%` references — resolving those
 * against this same bag is [org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver]'s
 * job, not this class's. What this module does *not* do is scope merging (project ⊕ config ⊕
 * step): that is a mapping-layer concern owned by whoever builds this model from the external
 * TeamCity client DTOs (the server — see docs/teamcity-validation-decision-log.md §5 decision 11).
 */
class Parameters(
    private val values: Map<String, String>,
) {
    operator fun get(name: String): String? = values[name]

    fun require(name: String): String = values[name] ?: throw NoSuchElementException("Parameter '$name' is not present")
}
