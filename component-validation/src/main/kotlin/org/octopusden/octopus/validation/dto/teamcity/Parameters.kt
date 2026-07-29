package org.octopusden.octopus.validation.dto.teamcity

/**
 * A read-only bag of TeamCity parameters (project/config/step level, depending on where it was
 * built from). Values may still contain unresolved `%paramName%` references — resolving those
 * against this same bag is [org.octopusden.octopus.validation.resolvers.teamcity.ParameterReferenceResolver]'s
 * job, not this class's. Scope merging (project ⊕ config ⊕ step) is a mapping-layer concern owned
 * by whoever builds this model from the external TeamCity client DTOs (the server, decision D11).
 */
class Parameters(
    private val values: Map<String, String>,
) {
    operator fun get(name: String): String? = values[name]

    fun require(name: String): String = values[name] ?: throw NoSuchElementException("Parameter '$name' is not present")
}
