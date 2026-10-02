package org.octopusden.octopus.components.registry.server.teamcity.validation

import org.octopusden.octopus.components.registry.server.teamcity.placement.TcCompileConfig
import org.octopusden.octopus.components.registry.server.teamcity.placement.compareVcsRoots
import org.octopusden.octopus.components.registry.server.teamcity.placement.repoDisplayName
import org.octopusden.octopus.validation.core.Status
import org.octopusden.octopus.validation.core.ValidationResult
import org.octopusden.octopus.validation.validators.type.TeamCityValidationType

/**
 * The "VCS roots differ from the registry" finding of a TeamCity project. Needs the registry's roots,
 * so it lives server-side next to the module validators; the comparison itself is the Diff's
 * [org.octopusden.octopus.components.registry.server.teamcity.placement.compareVcsRoots].
 */
object VcsRootsValidation {
    fun check(
        componentKey: String,
        registryVcsPaths: List<String>,
        compileConfigs: List<TcCompileConfig>,
    ): ValidationResult? {
        val diff = compareVcsRoots(registryVcsPaths, compileConfigs)
        val parts = listOfNotNull(
            diff.extra.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "TeamCity also attaches ",
                separator = ", ",
            ) { "${it.repo} (${it.buildTypeIds.joinToString(", ")})" },
            diff.missing.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "no compile configuration attaches ",
                separator = ", ",
            ) { repoDisplayName(it) },
        )
        if (parts.isEmpty()) return null
        return ValidationResult(
            TeamCityValidationType.VCS_ROOTS_DIFFER_FROM_REGISTRY,
            Status.WARNING,
            "VCS roots differ from the registry ($componentKey): " + parts.joinToString("; "),
        )
    }

    /** One finding for several components' findings (the store keeps one row per project and type). */
    fun merge(findings: List<ValidationResult>): ValidationResult? =
        findings.firstOrNull()?.copy(message = findings.joinToString(" | ") { it.message.orEmpty() })
}
