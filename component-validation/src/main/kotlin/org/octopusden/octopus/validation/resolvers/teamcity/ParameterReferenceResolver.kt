package org.octopusden.octopus.validation.resolvers.teamcity

import org.octopusden.octopus.validation.dto.teamcity.Parameters

/**
 * Recursively resolves TeamCity `%paramName%` references within a parameter value, looking up
 * each referenced name in the same [Parameters] bag, until no resolvable `%...%` pattern remains.
 * A reference to a **missing** parameter is left as the literal `%paramName%` text in the result
 * — TeamCity itself doesn't fail a whole value just because one embedded reference doesn't
 * resolve, and neither does this org's own server-side `TeamcityClientConfig.kt`
 * (`resolveReferences` leaves an unresolved token literal at the point of substitution). A
 * reference **cycle**, however, makes the whole value unresolved (`null`) — once a name is
 * already being expanded on the current path, resolving it again can never terminate, so the
 * whole call fails rather than looping or guessing.
 *
 * Stateless — a plain utility, called directly rather than injected as a collaborator.
 */
object ParameterReferenceResolver {
    /** Look up [parameterName] in [parameters] and resolve its value's reference chain, if any. */
    fun resolveParameter(
        parameters: Parameters,
        parameterName: String,
    ): String? = resolveParam(parameters, parameterName, mutableSetOf())

    /** Resolve any `%paramName%` references embedded in an arbitrary [value] against [parameters]. */
    fun resolveValue(
        parameters: Parameters,
        value: String,
    ): String? = resolveValue(parameters, value, mutableSetOf())

    private fun resolveParam(
        parameters: Parameters,
        name: String,
        visited: MutableSet<String>,
    ): String? {
        if (!visited.add(name)) return null // cycle: name is already being expanded on this path
        val raw = parameters[name] ?: run {
            visited.remove(name)
            return null
        } // the named parameter itself doesn't exist
        val resolved = resolveValue(parameters, raw, visited)
        visited.remove(name) // backtrack: sibling/later references to the same name aren't cycles
        return resolved
    }

    private fun resolveValue(
        parameters: Parameters,
        value: String,
        visited: MutableSet<String>,
    ): String? {
        var current = value
        var searchFrom = 0
        while (true) {
            val match = REFERENCE.find(current, searchFrom) ?: break
            val refName = match.groupValues[1]
            if (parameters[refName] == null) {
                // Missing parameter: leave this reference as literal text and keep scanning past it.
                searchFrom = match.range.last + 1
                continue
            }
            val refResolved = resolveParam(parameters, refName, visited) ?: return null // cycle: fail the whole value
            current = current.replaceRange(match.range, refResolved)
            searchFrom = 0 // restart: the replacement text may itself contain references
        }
        return current
    }

    private val REFERENCE = Regex("""%([\w.\-]+)%""")
}
