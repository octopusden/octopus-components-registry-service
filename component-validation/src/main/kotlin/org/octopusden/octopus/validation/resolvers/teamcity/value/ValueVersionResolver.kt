package org.octopusden.octopus.validation.resolvers.teamcity.value

import org.octopusden.octopus.validation.dto.teamcity.ToolVersion

/** Given an already reference-resolved raw string, derive a specific tool's version from it, if any. */
fun interface ValueVersionResolver<out V : ToolVersion> {
    fun resolve(value: String): V?
}
