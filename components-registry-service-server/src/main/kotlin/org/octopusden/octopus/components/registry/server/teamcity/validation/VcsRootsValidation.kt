package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.teamcity.placement.TcCompileConfig
import org.octopusden.octopus.validation.core.ValidationResult

/**
 * The "VCS roots differ from the registry" finding of a TeamCity project. Needs the registry's roots,
 * so it lives server-side next to the module validators; the comparison itself is the Diff's
 * [org.octopusden.octopus.components.registry.server.teamcity.placement.compareVcsRoots].
 */
object VcsRootsValidation {
    fun check(
        registryVcsPaths: List<String>,
        compileConfigs: List<TcCompileConfig>,
    ): ValidationResult? = null
}
